//! The client half of the service directory: a service announcing itself to trackers.
//!
//! A rendezvous does the same thing a peer already does — announce to every configured tracker,
//! independently, and treat a failure as the steady state rather than an error. What it gets back is
//! the piece that makes migration seamless: the ack carries the *other* rendezvous points the tracker
//! knows, so a draining service already holds the replacement list it needs to hand its peers.
//!
//! Announcing to several trackers is not optional in effect: a rendezvous announced to one tracker is
//! invisible to every peer that asks a different one, which is the tracker-partition problem
//! (`docs/tracker/REFERENCE.md` §16) applied one level up.

use std::io;
use std::time::Duration;

use nodera_codec::framing;
use nodera_codec::service::{
    ServiceAnnounce, ServiceDirectoryEntry, ServiceMessage, ServiceRecord,
};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

/// How long a single announce exchange may take before it is abandoned.
///
/// Short on purpose: an unreachable tracker must not stall a shutdown that is waiting to publish a
/// drain notice, and the next announce is only one interval away.
pub const EXCHANGE_TIMEOUT: Duration = Duration::from_secs(5);

/// What one tracker said in reply to an announce.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AnnounceOutcome {
    /// The tracker that answered.
    pub endpoint: String,
    /// Whether the record was admitted.
    pub accepted: bool,
    /// The tracker's requested announce interval.
    pub next_announce_after_seconds: u32,
    /// A stable rejection code when refused.
    pub reason: String,
    /// Sibling services the tracker knows — the announcer's own failover set.
    pub directory: Vec<ServiceDirectoryEntry>,
}

/// Announce `record` (with `signature`) to one tracker and return what it said.
pub async fn announce_to(
    endpoint: &str,
    record: &ServiceRecord,
    signature: &[u8],
) -> io::Result<AnnounceOutcome> {
    let frame = ServiceMessage::Announce(ServiceAnnounce {
        record: record.clone(),
        signature: signature.to_vec(),
    })
    .encode();
    let reply = tokio::time::timeout(EXCHANGE_TIMEOUT, exchange(endpoint, &frame))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "announce exchange timed out"))??;

    match ServiceMessage::decode(&reply) {
        Ok(ServiceMessage::AnnounceAck(ack)) => Ok(AnnounceOutcome {
            endpoint: endpoint.to_owned(),
            accepted: ack.accepted,
            next_announce_after_seconds: ack.next_announce_after_seconds,
            reason: ack.reason,
            directory: ack.directory,
        }),
        Ok(other) => Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("expected a service announce ack, got tag {}", other.tag()),
        )),
        Err(e) => Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("undecodable announce ack: {e}"),
        )),
    }
}

/// Announce to every endpoint, returning one outcome per tracker that answered.
///
/// Errors are dropped rather than propagated: a tracker being down degrades discovery and nothing
/// else, and a service that refused to start because one tracker was unreachable would have made a
/// discovery hint load-bearing for its own availability.
pub async fn announce_to_all(
    endpoints: &[String],
    record: &ServiceRecord,
    signature: &[u8],
) -> Vec<AnnounceOutcome> {
    let mut outcomes = Vec::new();
    for endpoint in endpoints {
        match announce_to(endpoint, record, signature).await {
            Ok(outcome) => outcomes.push(outcome),
            Err(e) => {
                eprintln!("nodera-service: announce to {endpoint} failed: {e}");
            }
        }
    }
    outcomes
}

/// Merge the sibling directories from several acks into one list, best score first.
///
/// A union, never an arbitration: two trackers that disagree about a service both contribute, and the
/// higher composite wins the ordering. Deduplication is by service id, so the same rendezvous listed
/// by three trackers appears once — with the best score any of them reported, because a peer that
/// measured it well is better evidence than a tracker that has not heard from it lately.
pub fn merge_directories(outcomes: &[AnnounceOutcome]) -> Vec<ServiceDirectoryEntry> {
    let mut merged: Vec<ServiceDirectoryEntry> = Vec::new();
    for outcome in outcomes {
        for entry in &outcome.directory {
            match merged
                .iter_mut()
                .find(|held| held.record.service == entry.record.service)
            {
                Some(held) => {
                    if entry.score.recomputed_composite() > held.score.recomputed_composite() {
                        *held = entry.clone();
                    }
                }
                None => merged.push(entry.clone()),
            }
        }
    }
    // Recomputed, never the transmitted composite: a tracker that inflated a favourite's number
    // must not be able to reorder somebody else's failover list.
    merged.sort_by(|a, b| {
        b.score
            .recomputed_composite()
            .cmp(&a.score.recomputed_composite())
            .then_with(|| a.record.service.msb.cmp(&b.record.service.msb))
            .then_with(|| a.record.service.lsb.cmp(&b.record.service.lsb))
    });
    merged
}

async fn exchange(endpoint: &str, frame: &[u8]) -> io::Result<Vec<u8>> {
    // The scheme comes off first. Every documented endpoint form in this project may carry one,
    // and a resolver handed `tcp://1.2.3.4:6969` reports that the name does not resolve — once, at
    // boot, after which the service runs perfectly and announces to nobody.
    let mut stream = TcpStream::connect(crate::endpoint::socket_target(endpoint)).await?;
    stream.set_nodelay(true)?;
    stream
        .write_all(
            &framing::frame(frame)
                .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e.to_string()))?,
        )
        .await?;
    stream.flush().await?;

    let mut header = [0u8; 4];
    stream.read_exact(&mut header).await?;
    let len = framing::decode_length(header)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e.to_string()))?;
    let mut body = vec![0u8; len];
    stream.read_exact(&mut body).await?;
    Ok(body)
}

#[cfg(test)]
mod tests {
    use super::*;
    use nodera_codec::service::{ServiceAnnounceAck, ServiceKind, ServiceLifecycle, ServiceScore};
    use nodera_codec::types::{NetworkId, NodeId};
    use tokio::net::TcpListener;

    fn record(id: u64) -> ServiceRecord {
        ServiceRecord {
            service: NodeId::new(id, id),
            public_key: vec![0x42; 44],
            kind: ServiceKind::Rendezvous,
            lifecycle: ServiceLifecycle::Serving,
            network_id: NetworkId::new(1, 2),
            routes: vec![format!("rdv{id}.example:25601")],
            version: "0.1.0".to_owned(),
            active_sessions: 0,
            max_sessions: 0,
            active_circuits: 0,
            max_circuits: 0,
            rejected_last_window: 0,
            issued_at_epoch_millis: 1,
            expires_at_epoch_millis: 2,
            drain_deadline_epoch_millis: 0,
        }
    }

    fn entry(id: u64, availability: u32) -> ServiceDirectoryEntry {
        ServiceDirectoryEntry {
            record: record(id),
            signature: vec![0x77; 64],
            score: ServiceScore {
                availability_permille: availability,
                rtt_p50_millis: 20,
                rtt_p95_millis: 40,
                capacity_permille: 1_000,
                freshness_permille: 1_000,
                reporter_count: 3,
                composite_permille: 0,
            }
            .with_composite(),
        }
    }

    fn outcome(entries: Vec<ServiceDirectoryEntry>) -> AnnounceOutcome {
        AnnounceOutcome {
            endpoint: "tracker.example:25600".to_owned(),
            accepted: true,
            next_announce_after_seconds: 120,
            reason: String::new(),
            directory: entries,
        }
    }

    #[test]
    fn merging_orders_by_recomputed_composite_and_deduplicates_by_service() {
        let merged = merge_directories(&[
            outcome(vec![entry(1, 500), entry(2, 900)]),
            outcome(vec![entry(1, 1_000), entry(3, 100)]),
        ]);
        assert_eq!(merged.len(), 3, "one row per service, not per tracker");
        assert_eq!(merged[0].record.service, NodeId::new(1, 1));
        // Service 1 was listed twice; the better measurement is the one that survives.
        assert_eq!(merged[0].score.availability_permille, 1_000);
        assert_eq!(merged[1].record.service, NodeId::new(2, 2));
        assert_eq!(merged[2].record.service, NodeId::new(3, 3));
    }

    #[test]
    fn an_inflated_composite_cannot_reorder_the_list() {
        // A tracker claiming 1000 for a service every peer measured as dead must not win the sort.
        let honest = entry(2, 900);
        let mut liar = entry(1, 0);
        liar.score = ServiceScore {
            composite_permille: 1_000,
            ..liar.score
        };
        let merged = merge_directories(&[outcome(vec![liar, honest])]);
        assert_eq!(merged[0].record.service, NodeId::new(2, 2));
    }

    #[tokio::test]
    async fn an_announce_round_trips_against_a_tracker_that_answers() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap().to_string();
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut header = [0u8; 4];
            stream.read_exact(&mut header).await.unwrap();
            let len = framing::decode_length(header).unwrap();
            let mut body = vec![0u8; len];
            stream.read_exact(&mut body).await.unwrap();
            let decoded = ServiceMessage::decode(&body).unwrap();
            assert!(matches!(decoded, ServiceMessage::Announce(_)));

            let ack = ServiceMessage::AnnounceAck(ServiceAnnounceAck {
                accepted: true,
                next_announce_after_seconds: 90,
                reason: String::new(),
                directory: vec![entry(5, 800)],
            })
            .encode();
            stream
                .write_all(&framing::frame(&ack).unwrap())
                .await
                .unwrap();
            stream.flush().await.unwrap();
        });

        let outcome = announce_to(&addr, &record(9), &[0x11; 64]).await.unwrap();
        assert!(outcome.accepted);
        assert_eq!(outcome.next_announce_after_seconds, 90);
        assert_eq!(outcome.directory.len(), 1);
        server.await.unwrap();
    }

    #[tokio::test]
    async fn an_announce_reaches_a_tracker_written_in_the_documented_tcp_form() {
        // The regression this whole `endpoint` module exists for. Every example config, the compose
        // file, `services/official.json` and the Java `Endpoint.parse` all use `tcp://host:port`,
        // and this path used to hand that string straight to the resolver. The service then ran
        // perfectly and announced to nobody, having said so once at boot.
        //
        // The test above passes a bare `host:port` — which is why it never caught this.
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap().to_string();
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut header = [0u8; 4];
            stream.read_exact(&mut header).await.unwrap();
            let len = framing::decode_length(header).unwrap();
            let mut body = vec![0u8; len];
            stream.read_exact(&mut body).await.unwrap();

            let ack = ServiceMessage::AnnounceAck(ServiceAnnounceAck {
                accepted: true,
                next_announce_after_seconds: 90,
                reason: String::new(),
                directory: vec![],
            })
            .encode();
            stream
                .write_all(&framing::frame(&ack).unwrap())
                .await
                .unwrap();
            stream.flush().await.unwrap();
        });

        let outcome = announce_to(&format!("tcp://{addr}"), &record(9), &[0x11; 64])
            .await
            .expect("a tcp:// endpoint is the documented form and must connect");
        assert!(outcome.accepted);
        server.await.unwrap();
    }

    #[tokio::test]
    async fn an_unreachable_tracker_is_a_dropped_outcome_not_a_failure() {
        // The property that keeps a discovery hint from becoming load-bearing for the service's own
        // availability: nothing here propagates.
        let outcomes = announce_to_all(
            &["127.0.0.1:1".to_owned(), "127.0.0.1:2".to_owned()],
            &record(1),
            &[0u8; 64],
        )
        .await;
        assert!(outcomes.is_empty());
    }

    #[tokio::test]
    async fn a_wrong_reply_tag_is_rejected_rather_than_misread() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap().to_string();
        tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut header = [0u8; 4];
            let _ = stream.read_exact(&mut header).await;
            let len = framing::decode_length(header).unwrap_or(0);
            let mut body = vec![0u8; len];
            let _ = stream.read_exact(&mut body).await;
            // Answer with a directory response where an ack belongs.
            let wrong = ServiceMessage::DirectoryResponse(
                nodera_codec::service::ServiceDirectoryResponse { entries: vec![] },
            )
            .encode();
            let _ = stream.write_all(&framing::frame(&wrong).unwrap()).await;
            let _ = stream.flush().await;
        });
        let err = announce_to(&addr, &record(1), &[0u8; 64])
            .await
            .unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
    }
}
