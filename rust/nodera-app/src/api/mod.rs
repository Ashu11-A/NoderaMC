//! The dashboard API: one model, one live link, one event.
//!
//! # What this replaces
//!
//! The app used to hand React the worker's wire type directly and let each page derive what it
//! needed. Three things followed from that, and all three were visible on screen:
//!
//! * **Zeros with no provenance.** Every tile rendered a number whether or not a worker had ever
//!   answered, so "the node has done nothing" and "we have never heard from the node" looked
//!   identical — a dashboard reporting `0 B`, `0 peers`, `0 ms` with total confidence and no way
//!   to tell which of those were measurements.
//! * **Derived values computed in the view.** Throughput was differenced across React renders,
//!   which are not samples, and the first frame once rendered gigabytes per second.
//! * **A two-second poll presented as live.** Nothing said when the picture was last refreshed, so
//!   a link that had silently stopped looked exactly like a quiet node.
//!
//! # The shape now
//!
//! ```text
//!   Java worker ──NODERA-WATCH──▶ link.rs ──▶ store.rs ──▶ nodera://dashboard ──▶ React
//!        (pushes on change)      (reconnects)  (revision,    (one typed event)
//!                                               rates, age)
//! ```
//!
//! * [`model`] — the typed view model. `Option` wherever "unknown" is a real answer, and a `link`
//!   block on every payload saying when it was taken and what carried it.
//! * [`store`] — the single mutable picture: revisioned, aged, with rates measured against a real
//!   clock.
//! * [`link`] — the connection to the worker: streams by preference, polls only for a worker too
//!   old to stream, and reports its own transitions rather than going quiet.
//! * [`commands`] — what React calls. Initial paint and on-demand detail only; steady state is the
//!   event.
//! * [`events`] — the second stream: what *happened* on the node. The state lane says a LAN world
//!   exists; this one says one was just opened, which is what a prompt needs.
//! * [`network`] — the round trips that are commands rather than state: answering the LAN prompt,
//!   browsing what is joinable, opening a tunnel, minting an invitation.
//! * [`modinstall`] — putting the NoderaMC mod into a Minecraft installation the app found.
//! * [`about`] — what this build is, and the generated attribution list for what it is built out of.

pub mod about;
pub mod commands;
pub mod events;
pub mod link;
pub mod model;
pub mod modinstall;
pub mod network;
pub mod storage;
pub mod store;
