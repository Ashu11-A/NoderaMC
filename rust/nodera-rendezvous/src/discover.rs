//! Assembling a `RendezvousPeers` page from the registry.
//!
//! Discovery is paged and bounded (rendezvous.md §8.5): a single query never enumerates a whole
//! namespace. The order is deterministic (by node id) so a cursor is stable across queries and a
//! retry does not reshuffle.

use crate::config::Config;
use crate::registry::{Namespace, Registry};
use nodera_codec::rendezvous::{RendezvousDiscover, RendezvousPeers, SignedRecord};

/// Build the answer to a discovery query.
///
/// A namespace nobody is in still gets an answer — an empty page, never silence, so a joining peer
/// does not hang.
pub fn answer(
    registry: &Registry,
    query: &RendezvousDiscover,
    config: &Config,
    now_millis: u64,
) -> RendezvousPeers {
    let ns = Namespace::new(query.network_id, query.genesis_hash.clone());
    let Some(entry) = registry.namespace(&ns) else {
        return RendezvousPeers {
            next_cursor: 0,
            records: Vec::new(),
        };
    };

    let live = entry.live_records(now_millis, config.registration_ttl_millis());
    let page_size = effective_limit(query.limit, config.discover_page_limit);
    let start = query.cursor as usize;

    let records: Vec<SignedRecord> = live
        .iter()
        .skip(start)
        .take(page_size)
        .map(|(_, r)| r.signed.clone())
        .collect();

    // A non-zero next cursor only when there is genuinely more to fetch, so a client stops paging
    // when it has seen everything.
    let next_cursor = if start + records.len() < live.len() {
        (start + records.len()) as u32
    } else {
        0
    };

    RendezvousPeers {
        next_cursor,
        records,
    }
}

/// The page size to use: the client's request clamped to the operator's ceiling, defaulting to the
/// ceiling when the client asks for `0`.
fn effective_limit(requested: u32, ceiling: usize) -> usize {
    if requested == 0 {
        ceiling
    } else {
        (requested as usize).min(ceiling)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::signed_record;
    use nodera_codec::types::{NetworkId, RegistrationEvent};

    const NET: NetworkId = NetworkId { msb: 1, lsb: 2 };

    fn config() -> Config {
        Config {
            discover_page_limit: 3,
            ..Config::default()
        }
    }

    fn registry_with(count: u8, now: u64) -> Registry {
        let mut reg = Registry::new();
        for n in 1..=count {
            let signed = signed_record(n, NET, b"world", RegistrationEvent::Register, 0);
            reg.apply(&signed, now, 300_000, 100, 100);
        }
        reg
    }

    fn query(cursor: u32, limit: u32) -> RendezvousDiscover {
        RendezvousDiscover {
            network_id: NET,
            genesis_hash: b"world".to_vec(),
            cursor,
            limit,
        }
    }

    #[test]
    fn an_unknown_namespace_answers_with_an_empty_page() {
        let reg = Registry::new();
        let page = answer(&reg, &query(0, 0), &config(), 1_000);
        assert!(page.records.is_empty());
        assert_eq!(page.next_cursor, 0);
    }

    #[test]
    fn a_page_is_bounded_and_the_cursor_walks_the_whole_set() {
        let reg = registry_with(7, 1_000);
        let first = answer(&reg, &query(0, 0), &config(), 1_000);
        assert_eq!(first.records.len(), 3, "page limit is a hard bound");
        assert_eq!(first.next_cursor, 3);

        let second = answer(&reg, &query(first.next_cursor, 0), &config(), 1_000);
        assert_eq!(second.records.len(), 3);
        assert_eq!(second.next_cursor, 6);

        let last = answer(&reg, &query(second.next_cursor, 0), &config(), 1_000);
        assert_eq!(last.records.len(), 1);
        assert_eq!(last.next_cursor, 0, "no more to fetch");
    }

    #[test]
    fn a_client_limit_below_the_ceiling_is_honoured() {
        let reg = registry_with(5, 1_000);
        let page = answer(&reg, &query(0, 2), &config(), 1_000);
        assert_eq!(page.records.len(), 2);
    }

    #[test]
    fn expired_records_drop_out_of_the_page() {
        let reg = registry_with(3, 1_000);
        let page = answer(&reg, &query(0, 0), &config(), 1_000 + 300_001);
        assert!(page.records.is_empty());
    }
}
