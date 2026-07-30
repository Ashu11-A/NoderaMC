//! `nodera-telemetry` — consented, schema-bounded, de-identified telemetry.
//!
//! The crate is both a **binary** (the ingest service; see `main.rs`) and a **library**, because
//! the two halves of the pipeline have to agree on one thing: what an event looks like. The
//! services that report (`nodera-tracker`, `nodera-rendezvous`) build events with
//! [`reporter::ServiceEvent`] and the receiver validates them against [`schema::REGISTRY`] — same
//! crate, so the two cannot drift the way two hand-maintained copies would.
//!
//! The privacy properties are documented on the modules that enforce them:
//!
//! * [`schema`] — the collection policy; no free-text value is representable.
//! * [`event`] — consent as a gate, validation as a filter.
//! * [`subject`] — the install id is never stored.
//! * [`geo`] — the source address becomes a country and an ASN, then it is gone.
//! * [`reporter`] — the service-side emitter, off unless an operator configures an endpoint.

pub mod config;
pub mod event;
pub mod geo;
pub mod limits;
pub mod reporter;
pub mod schema;
pub mod service;
pub mod sink;
pub mod subject;
pub mod wire;
