//! Plumbing every Nodera infrastructure service needs and neither should own alone.
//!
//! `nodera-tracker` and `nodera-rendezvous` are separate binaries with separate protocols, but four
//! concerns are identical in both, and the first version of each was written twice with small
//! differences that mattered:
//!
//! * [`identity`] — the stable `NodeId` + Ed25519 key a service signs **its own address record** with.
//!   Nothing else. No peer record, no vote, no world.
//! * [`directory`] — announcing that record to trackers, and merging the sibling lists that come back.
//! * [`drain`] — refusing new work, letting in-flight work finish, and reporting what a grace period
//!   had to cut.
//! * [`update`] — noticing that the published binary is not the one running, verifying the replacement,
//!   and swapping it.
//! * [`env`] — reading configuration out of the environment, so an image can be run without a file.
//!
//! ## The rule this crate does not break
//!
//! Task 0 §4 rule 7: *Rust service crates carry no game, consensus, or storage logic.* Nothing here
//! touches a world, a region, or a state root. A service's signature says "this is where I am and this
//! is when I am leaving" — a routing hint peers verify and are free to ignore, never authority.

#![deny(missing_docs)]

pub mod directory;
pub mod drain;
pub mod env;
pub mod identity;
pub mod lifecycle;
pub mod update;
