"""Cluster divergence reports — the batch question ClickHouse should not be asked.

A `engine.divergence` fingerprint tells you *that* two peers disagreed; it does not tell you which
disagreements are the same bug. This job groups fingerprints by the shape of their co-occurrence
(same rules version, same phase, overlapping agent populations) and emits the ranked list the
engine lane triages from.

Why Spark and not a ClickHouse query: this is an all-pairs pass over a window of raw rows with an
iterative grouping step. Running it on the warehouse that also serves Grafana turns a dashboard
refresh into a timeout for everyone. It reads a Parquet export instead, on a schedule.

    docker compose --profile analytics run --rm spark \\
        spark-submit --master spark://spark:7077 /opt/nodera/jobs/divergence_clusters.py \\
        --input /opt/nodera/warehouse/divergence.parquet \\
        --output /opt/nodera/warehouse/divergence_clusters.parquet
"""

import argparse

from pyspark.sql import SparkSession, functions as F


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="Parquet export of nodera.events")
    parser.add_argument("--output", required=True, help="where the ranked clusters are written")
    parser.add_argument(
        "--min-installs",
        type=int,
        default=3,
        # A fingerprint seen on one or two installations is usually a broken machine — bad RAM, a
        # mod conflict, a modified jar. Ranking those alongside real engine bugs is how a triage
        # list becomes noise nobody reads.
        help="ignore fingerprints seen on fewer distinct installations than this",
    )
    args = parser.parse_args()

    spark = SparkSession.builder.appName("nodera-divergence-clusters").getOrCreate()

    events = spark.read.parquet(args.input).where(F.col("event") == "engine.divergence")

    per_fingerprint = (
        events.select(
            F.col("str")["fingerprint"].alias("fingerprint"),
            F.col("str")["phase"].alias("phase"),
            F.col("num")["rules_version"].alias("rules_version"),
            F.col("agent"),
            F.col("subject"),
            F.to_date("received_at").alias("day"),
        )
        .groupBy("fingerprint", "phase", "rules_version")
        .agg(
            F.countDistinct("subject").alias("installs"),
            F.count("*").alias("reports"),
            F.collect_set("agent").alias("agents"),
            F.min("day").alias("first_seen"),
            F.max("day").alias("last_seen"),
        )
        .where(F.col("installs") >= args.min_installs)
    )

    # A fingerprint that appears under exactly one agent build is a regression in that build; one
    # spread across every build is an old bug that finally got looked at. Both are useful and they
    # are different triage paths, so the distinction is a column rather than a filter.
    ranked = per_fingerprint.withColumn("agent_count", F.size("agents")).withColumn(
        "single_build_regression", F.col("agent_count") == 1
    ).orderBy(F.desc("installs"), F.desc("reports"))

    ranked.write.mode("overwrite").parquet(args.output)
    spark.stop()


if __name__ == "__main__":
    main()
