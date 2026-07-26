-- The Nodera telemetry warehouse.
--
-- Three decisions are load-bearing here:
--
-- 1. **Retention is a TTL on the table, not a cleanup job.** A deletion policy that lives in cron
--    is a deletion policy that stops running one day and nobody notices for a year. Raw rows live
--    30 days; the aggregates that answer trend questions live 400 and contain no subject at all.
-- 2. **Attributes are typed Maps, not a JSON blob.** The ingest service already split them
--    (`num`/`str`/`flag`), so a query reads `num['tps_bucket']` with no per-row parsing.
-- 3. **`subject` never leaves the raw table.** Every rollup below aggregates it away through
--    `uniqState`, so the long-lived data cannot be walked back to an installation even in
--    principle.

CREATE DATABASE IF NOT EXISTS nodera;

-- ---------------------------------------------------------------------------------------------
-- Raw events, as written by nodera-telemetry.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS nodera.events
(
    `schema`      UInt16,
    `received_at` DateTime64(3),
    `at`          DateTime64(3),
    `source`      LowCardinality(String),
    `subject`     String,
    `agent`       LowCardinality(String),
    `country`     LowCardinality(String),
    `asn`         UInt32,
    `event`       LowCardinality(String),
    `num`         Map(String, Int64),
    `str`         Map(String, String),
    `flag`        Map(String, UInt8),
    `collected_at` DateTime64(3)
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(received_at)
ORDER BY (event, received_at, source)
TTL toDateTime(received_at) + INTERVAL 30 DAY;

-- ---------------------------------------------------------------------------------------------
-- The bus consumer. A Kafka engine table is a queue, not a store: it is read exactly once by the
-- materialised view below, which is what actually persists the rows.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS nodera.events_queue
(
    `schema`      UInt16,
    `received_at` Int64,
    `at`          Int64,
    `source`      String,
    `subject`     String,
    `agent`       String,
    `country`     String,
    `asn`         UInt32,
    `event`       String,
    `num`         Map(String, Int64),
    `str`         Map(String, String),
    `flag`        Map(String, UInt8),
    `collected_at` Int64
)
ENGINE = Kafka
SETTINGS kafka_broker_list = 'redpanda:9092',
         kafka_topic_list = 'nodera.telemetry.raw.v1',
         kafka_group_name = 'clickhouse-nodera-telemetry',
         kafka_format = 'JSONEachRow',
         -- A single malformed message must not stall the consumer group forever.
         kafka_skip_broken_messages = 100;

CREATE MATERIALIZED VIEW IF NOT EXISTS nodera.events_from_queue TO nodera.events AS
SELECT
    `schema`,
    fromUnixTimestamp64Milli(received_at)  AS received_at,
    fromUnixTimestamp64Milli(at)           AS at,
    source, subject, agent, country, asn, event, num, str, flag,
    fromUnixTimestamp64Milli(collected_at) AS collected_at
FROM nodera.events_queue;

-- ---------------------------------------------------------------------------------------------
-- Rollup 1 — the shape almost every dashboard panel wants: how many of each event, from how many
-- distinct installations, where. Subject survives only inside an aggregate state.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS nodera.events_hourly
(
    `hour`     DateTime,
    `source`   LowCardinality(String),
    `event`    LowCardinality(String),
    `country`  LowCardinality(String),
    `agent`    LowCardinality(String),
    `events`   AggregateFunction(count),
    `subjects` AggregateFunction(uniq, String)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(hour)
ORDER BY (hour, source, event, country, agent)
TTL hour + INTERVAL 400 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS nodera.events_hourly_mv TO nodera.events_hourly AS
SELECT
    toStartOfHour(received_at) AS hour,
    source, event, country, agent,
    countState()               AS events,
    uniqState(subject)         AS subjects
FROM nodera.events
GROUP BY hour, source, event, country, agent;

-- ---------------------------------------------------------------------------------------------
-- Rollup 2 — reachability, the single most actionable number the project collects: what fraction
-- of joins succeed, split by the path they took and the country they came from. If hole punching
-- is failing in a region, this is where it shows up before anyone files a bug.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS nodera.join_outcomes_hourly
(
    `hour`      DateTime,
    `country`   LowCardinality(String),
    `path`      LowCardinality(String),
    `failure`   LowCardinality(String),
    `attempts`  AggregateFunction(count),
    `successes` AggregateFunction(sum, UInt8)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(hour)
ORDER BY (hour, country, path, failure)
TTL hour + INTERVAL 400 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS nodera.join_outcomes_hourly_mv TO nodera.join_outcomes_hourly AS
SELECT
    toStartOfHour(received_at)      AS hour,
    country,
    str['path']                     AS path,
    str['failure']                  AS failure,
    countState()                    AS attempts,
    sumState(flag['ok'])            AS successes
FROM nodera.events
WHERE event = 'world.join'
GROUP BY hour, country, path, failure;

-- ---------------------------------------------------------------------------------------------
-- Rollup 3 — divergence, the metric the project's central bet lives or dies by. A fingerprint
-- appearing on many distinct installations is a determinism bug in the wild, and it must be
-- visible without querying raw rows.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS nodera.divergence_daily
(
    `day`           Date,
    `phase`         LowCardinality(String),
    `rules_version` UInt16,
    `fingerprint`   String,
    `agent`         LowCardinality(String),
    `reports`       AggregateFunction(count),
    `subjects`      AggregateFunction(uniq, String)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(day)
ORDER BY (day, phase, fingerprint, agent)
TTL day + INTERVAL 400 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS nodera.divergence_daily_mv TO nodera.divergence_daily AS
SELECT
    toDate(received_at)                    AS day,
    str['phase']                           AS phase,
    toUInt16(num['rules_version'])         AS rules_version,
    str['fingerprint']                     AS fingerprint,
    agent,
    countState()                           AS reports,
    uniqState(subject)                     AS subjects
FROM nodera.events
WHERE event = 'engine.divergence'
GROUP BY day, phase, rules_version, fingerprint, agent;

-- ---------------------------------------------------------------------------------------------
-- Convenience views for humans and dashboards.
-- ---------------------------------------------------------------------------------------------
CREATE VIEW IF NOT EXISTS nodera.v_events_hourly AS
SELECT hour, source, event, country, agent,
       countMerge(events)  AS event_count,
       uniqMerge(subjects) AS subject_count
FROM nodera.events_hourly
GROUP BY hour, source, event, country, agent;

-- The output columns are deliberately NOT named after the aggregate-state columns they merge.
-- `sumMerge(successes) AS successes` shadows the column inside the same SELECT, so the percentage
-- expression below would re-merge the already-merged UInt64 and the view would fail to create with
-- "Illegal type UInt64 of argument for aggregate function with Merge suffix". That is exactly how
-- this schema failed the first time CI ran it.
CREATE VIEW IF NOT EXISTS nodera.v_join_success AS
SELECT hour, country, path,
       countMerge(attempts) AS attempt_count,
       sumMerge(successes)  AS success_count,
       round(100 * success_count / nullIf(attempt_count, 0), 1) AS success_percent
FROM nodera.join_outcomes_hourly
GROUP BY hour, country, path;
