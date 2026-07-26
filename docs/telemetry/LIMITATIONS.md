# Telemetry — Limitations Register

<!-- AI-AGENT-INSTRUCTION: NORMATIVE for the telemetry category. "Permanent" is banned. Every §B row
     has an owning task and an EXIT TEST. A newly discovered limitation enters §B as OPEN with a
     path, an owner, and an exit test BEFORE the discovering PR merges. Never delete a row — move it
     to LIMITATIONS.fixed.md with its evidence. Note §C: some properties that LOOK like limitations
     are the privacy model working as designed and must not be "fixed". -->

**Category:** telemetry · **Last audit:** 2026-07-25 · Open or retiring rows: **4**

Status values: `OPEN` → `RETIRING` → `RETIRED` (row moves to
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)).

---

## §A — Envelope constraints

**A-T1 — An opt-in sample is not the population.** Self-selection cannot be corrected by any amount
of engineering: the installations that share telemetry are the ones whose owners chose to, and the
direction of that bias is unknowable from inside the sample. This is a fact of measurement, not a
defect. It is *satisfied* — not fixed — by never presenting a telemetry figure as a population count
and by labelling every published number as an opt-in sample ([telemetry 3](Task.3.md)).

---

## §B — Staged capabilities

| Row | Status | What is missing | Owning task | Exit test |
|---|---|---|---|---|
| **L-72** | OPEN | The operator holds the pseudonymisation secret, so during a *current* rotation period they could recompute the mapping from install id to subject. Rotation bounds the window; it does not remove the capability. Elimination path: move the derivation to a key the ingest process holds only in memory and discards on rotation, so past periods are unrecoverable even to the operator | [1](Task.1.md) | A restart-and-rotate test proving a previous period's subjects cannot be reproduced from the configuration alone |
| **L-73** | OPEN | The ingest listener is plaintext. Reports carry no secrets and no identifiers, but a network observer still learns that a given address reports telemetry and roughly how much. TLS is available only by putting the `edge` proxy in front. Elimination path: native TLS in the service, or making the proxy non-optional in the shipped deployment | [1](Task.1.md) | A test that a non-TLS connection to the public endpoint is refused once the deployment declares itself public |
| **L-74** | OPEN | The compose stack is single-node: no replication, no backup, no restore procedure. A disk loss loses collected history — which costs insight only, never correctness | [2](Task.2.md) | A documented and exercised backup/restore of the ClickHouse volume in CI |
| **L-75** | OPEN | Nothing is emitting yet, so every claim about what the pipeline *shows* is untested against real data. The receiver is proven; the population is not | [3](Task.3.md) | The first dashboard answering a §1.1 question from real reports, cited in `PROGRESS.md` |

---

## §C — The privacy model working as designed

These look like gaps and must not be "fixed":

- **Telemetry cannot be trusted, and is not.** Reports are unsigned, so anyone can submit anything
  within the registry. That is deliberate ([`../plans/Plan.6.md`](../plans/Plan.6.md) D3): signing
  would bind measurements to node identities. Because telemetry has no authority (D2), a poisoned
  aggregate costs a wrong graph, never a wrong world. Do not "fix" this by requiring signatures.
- **A peer cannot be identified from its telemetry, including by the project.** There is no path
  from a stored row back to a node id, an address, or a world. This makes some support questions
  ("why is *this* user's join failing?") unanswerable from telemetry. That is the intended trade.
- **The registry is restrictive on purpose.** Adding a field is a policy change, not a code change.
  "It would be useful to know X" is not sufficient reason; it must survive the D5 test — can the
  value carry prose, a name, or a path? — and be documented in Plan.6 §4 in the same commit.
- **Coarse geolocation is coarse forever.** Country and ASN only. Requests for city-level or
  finer resolution are refused: the questions this project has are about network reachability, and
  country/ASN answers them.
