//! World deletion records — the one instruction on this network that destroys something.
//!
//! A tracker is not trusted with this decision and does not make it: it decodes the record, checks
//! the same four things every peer checks, and either forgets the world or ignores the message.
//! Nothing here signs, so a compromised tracker can refuse to forget a world — it can never delete
//! somebody else's.
//!
//! Byte-exact port of `storage/WorldTombstone.java` and `storage/WorldOwnership.java`.

use crate::sig;
use crate::tags::{message_tags, type_tags};
use crate::{CanonicalReader, CanonicalWriter, CodecError, Result, ENCODING_VERSION};

/// A world's public key bound to the peer that created it, signed by both halves.
///
/// Wire form: `[u16 WORLD_OWNERSHIP][u16 v][Bytes worldId][Bytes worldPublicKey][NodeId owner]
/// [Bytes ownerPublicKey][u64 createdAtEpoch][Bytes worldSignature][Bytes ownerSignature]`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorldOwnership {
    /// The world this claim is about.
    pub world_id: Vec<u8>,
    /// The world's own public key — its administrative root.
    pub world_public_key: Vec<u8>,
    /// The creating peer.
    pub owner_node_id: crate::types::NodeId,
    /// That peer's public key.
    pub owner_public_key: Vec<u8>,
    /// When the world was created, epoch millis.
    pub created_at_epoch: i64,
    /// Signature by the world key over the signed portion.
    pub world_signature: Vec<u8>,
    /// Signature by the owner's node key over the same bytes.
    pub owner_signature: Vec<u8>,
    /// The exact bytes both signatures cover, kept from the frame rather than re-encoded.
    ///
    /// Re-encoding a decoded value to check a signature over it is the classic way a second
    /// implementation ends up accepting something the first would not: any disagreement about
    /// canonical form silently becomes a verification difference.
    signed_portion: Vec<u8>,
}

impl WorldOwnership {
    /// Decode one claim, consuming exactly its bytes.
    pub fn decode(r: &mut CanonicalReader<'_>) -> Result<Self> {
        let start = r.position();
        r.read_frame_header(type_tags::WORLD_OWNERSHIP, ENCODING_VERSION)?;
        let world_id = r.read_bytes_vec()?;
        let world_public_key = r.read_bytes_vec()?;
        let owner_node_id = crate::types::NodeId::decode(r)?;
        let owner_public_key = r.read_bytes_vec()?;
        let created_at_epoch = r.read_i64()?;
        let signed_portion = r.slice(start, r.position())?.to_vec();
        let world_signature = r.read_bytes_vec()?;
        let owner_signature = r.read_bytes_vec()?;
        Ok(Self {
            world_id,
            world_public_key,
            owner_node_id,
            owner_public_key,
            created_at_epoch,
            world_signature,
            owner_signature,
            signed_portion,
        })
    }

    /// Whether both signatures verify. One is never "partially valid".
    pub fn verify(&self) -> bool {
        sig::verify(
            &self.world_public_key,
            &self.signed_portion,
            &self.world_signature,
        )
        .is_ok()
            && sig::verify(
                &self.owner_public_key,
                &self.signed_portion,
                &self.owner_signature,
            )
            .is_ok()
    }
}

/// The owner's signed request that the network forget a world.
///
/// Wire form: `[u16 WORLD_TOMBSTONE][u16 v][Bytes worldId][Bytes ownershipRecord]
/// [u64 issuedAtEpoch][String reason][Bytes worldSignature][Bytes ownerSignature]`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorldTombstone {
    /// The world to forget.
    pub world_id: Vec<u8>,
    /// The ownership claim carried inline as the receiver's evidence.
    pub ownership_record: Vec<u8>,
    /// When the owner issued it, epoch millis.
    pub issued_at_epoch: i64,
    /// The owner's own words, for a person reading a log. Never load-bearing.
    pub reason: String,
    /// Signature by the world key over the signed portion.
    pub world_signature: Vec<u8>,
    /// Signature by the owner's node key over the same bytes.
    pub owner_signature: Vec<u8>,
    /// The exact signed bytes, kept from the frame.
    signed_portion: Vec<u8>,
    /// The whole record as received — what a tracker relays onward, unaltered.
    encoded: Vec<u8>,
}

impl WorldTombstone {
    /// Decode one tombstone, consuming exactly its bytes.
    pub fn decode(r: &mut CanonicalReader<'_>) -> Result<Self> {
        let start = r.position();
        r.read_frame_header(type_tags::WORLD_TOMBSTONE, ENCODING_VERSION)?;
        let world_id = r.read_bytes_vec()?;
        let ownership_record = r.read_bytes_vec()?;
        let issued_at_epoch = r.read_i64()?;
        let reason = r.read_string()?;
        let signed_portion = r.slice(start, r.position())?.to_vec();
        let world_signature = r.read_bytes_vec()?;
        let owner_signature = r.read_bytes_vec()?;
        let encoded = r.slice(start, r.position())?.to_vec();
        if world_id.is_empty() {
            return Err(CodecError::Malformed(
                "a tombstone must name a world".into(),
            ));
        }
        Ok(Self {
            world_id,
            ownership_record,
            issued_at_epoch,
            reason,
            world_signature,
            owner_signature,
            signed_portion,
            encoded,
        })
    }

    /// Decode a standalone frame, requiring it to be consumed entirely.
    pub fn from_bytes(frame: &[u8]) -> Result<Self> {
        let mut r = CanonicalReader::new(frame);
        let decoded = Self::decode(&mut r)?;
        r.expect_end()?;
        Ok(decoded)
    }

    /// The record exactly as it arrived — the form to relay.
    pub fn encoded(&self) -> &[u8] {
        &self.encoded
    }

    /// The world id, lowercase hex: the key every registry and cache uses.
    pub fn world_id_hex(&self) -> String {
        self.world_id.iter().map(|b| format!("{b:02x}")).collect()
    }

    /// The embedded ownership claim, if it is both readable and genuine.
    ///
    /// Empty means refuse. "Cannot verify" is never "assume the sender is honest".
    pub fn ownership(&self) -> Option<WorldOwnership> {
        let claim = WorldOwnership::from_bytes(&self.ownership_record).ok()?;
        claim.verify().then_some(claim)
    }

    /// The whole chain: is this really this world's owner asking?
    ///
    /// Everything needed is inside the record, so a tracker that has never seen the world reaches
    /// the same answer as the peer that hosted it — which is what lets a deletion be relayed by
    /// anyone without the relay being trusted.
    pub fn verify(&self) -> bool {
        let Some(claim) = self.ownership() else {
            return false;
        };
        // A genuine claim for a DIFFERENT world would otherwise let its owner delete this one.
        if claim.world_id != self.world_id {
            return false;
        }
        sig::verify(
            &claim.world_public_key,
            &self.signed_portion,
            &self.world_signature,
        )
        .is_ok()
            && sig::verify(
                &claim.owner_public_key,
                &self.signed_portion,
                &self.owner_signature,
            )
            .is_ok()
    }
}

impl WorldOwnership {
    /// Decode a standalone claim frame, requiring it to be consumed entirely.
    pub fn from_bytes(frame: &[u8]) -> Result<Self> {
        let mut r = CanonicalReader::new(frame);
        let decoded = Self::decode(&mut r)?;
        r.expect_end()?;
        Ok(decoded)
    }
}

/// A deletion on its way between peers: the world it names, and the record that proves it.
///
/// The tombstone travels as opaque canonical bytes. The envelope's `world_id` is a routing
/// convenience and is **never** believed over the signed record — see [`WorldDeletionGossip::verified`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorldDeletionGossip {
    /// The world named by the envelope.
    pub world_id: Vec<u8>,
    /// Canonical `WorldTombstone` bytes.
    pub encoded_tombstone: Vec<u8>,
}

impl WorldDeletionGossip {
    /// Decode a full message frame (tag 66).
    pub fn decode(frame: &[u8]) -> Result<Self> {
        let mut r = CanonicalReader::new(frame);
        r.read_frame_header(message_tags::WORLD_DELETION_GOSSIP, ENCODING_VERSION)?;
        let world_id = r.read_bytes_vec()?;
        let encoded_tombstone = r.read_bytes_vec()?;
        r.expect_end()?;
        Ok(Self {
            world_id,
            encoded_tombstone,
        })
    }

    /// Re-encode the frame.
    pub fn encode(&self) -> Vec<u8> {
        let mut w = CanonicalWriter::new();
        w.write_frame_header(message_tags::WORLD_DELETION_GOSSIP, ENCODING_VERSION);
        w.write_bytes(&self.world_id);
        w.write_bytes(&self.encoded_tombstone);
        w.into_vec()
    }

    /// The tombstone, but only if it verifies **and** names the world the envelope claims.
    ///
    /// Honouring the envelope over the proof would let any relay point a genuine deletion at a
    /// different world — the one attack that costs nothing to mount, since relaying is open to
    /// everyone by design.
    pub fn verified(&self) -> Option<WorldTombstone> {
        let tombstone = WorldTombstone::from_bytes(&self.encoded_tombstone).ok()?;
        if tombstone.world_id != self.world_id {
            return None;
        }
        tombstone.verify().then_some(tombstone)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The Java-emitted golden deletion, as a peer would receive it: an `NDR2` frame.
    fn golden_frame() -> Vec<u8> {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .parent()
            .and_then(std::path::Path::parent)
            .expect("repo root")
            .join("fixtures/wire/world-deletion-gossip.bin");
        std::fs::read(&path).unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()))
    }

    /// The strict canonical payload inside that frame — a deletion is a CONSENSUS kind, so the
    /// tolerant plane carries it opaque and untouched.
    fn golden() -> Vec<u8> {
        crate::wire::consensus_payload(&golden_frame())
            .expect("the golden frame carries an opaque consensus payload")
            .to_vec()
    }

    #[test]
    fn a_genuine_deletion_from_java_verifies_here() {
        let gossip = WorldDeletionGossip::decode(&golden()).expect("decode");
        let tombstone = gossip
            .verified()
            .expect("the Java-signed deletion must verify");

        assert_eq!(tombstone.world_id, gossip.world_id);
        assert!(tombstone.ownership().is_some());
        assert_eq!(gossip.encode(), golden(), "re-encoding must be byte-exact");
    }

    /// Flipping any bit of the signed prefix must break it. This is the property the whole feature
    /// rests on: without it a relay could edit a deletion in flight and still be believed.
    #[test]
    fn every_tampered_byte_is_refused() {
        let original = golden();
        let decoded = WorldDeletionGossip::decode(&original).expect("decode");
        let mut refused = 0;
        for i in 0..original.len() {
            let mut mutated = original.clone();
            mutated[i] ^= 0x01;
            if mutated == original {
                continue;
            }
            match WorldDeletionGossip::decode(&mutated) {
                // A decode failure is a refusal too — the byte was structural.
                Err(_) => refused += 1,
                Ok(gossip) => {
                    assert!(
                        gossip.verified().is_none(),
                        "byte {i} could be flipped and the deletion still accepted"
                    );
                    refused += 1;
                }
            }
        }
        assert_eq!(refused, original.len(), "every byte matters");
        assert!(decoded.verified().is_some(), "the original still verifies");
    }

    #[test]
    fn a_deletion_pointed_at_another_world_is_refused() {
        let gossip = WorldDeletionGossip::decode(&golden()).expect("decode");
        let redirected = WorldDeletionGossip {
            world_id: vec![0xAA; 32],
            encoded_tombstone: gossip.encoded_tombstone.clone(),
        };

        // The proof is genuine and the envelope is a lie; believing the envelope would delete a
        // world its owner never mentioned.
        assert!(redirected.verified().is_none());
    }

    #[test]
    fn a_deletion_with_its_evidence_stripped_is_refused() {
        let gossip = WorldDeletionGossip::decode(&golden()).expect("decode");
        let tombstone = WorldTombstone::from_bytes(&gossip.encoded_tombstone).expect("decode");
        let mut stripped = tombstone.clone();
        stripped.ownership_record = Vec::new();

        // "Cannot verify" has to mean "refuse", never "assume the sender is honest".
        assert!(!stripped.verify());
        assert!(stripped.ownership().is_none());
    }

    #[test]
    fn hostile_frames_error_instead_of_panicking() {
        assert!(WorldDeletionGossip::decode(&[]).is_err());
        assert!(WorldDeletionGossip::decode(&[0x00, 0x42]).is_err());
        // A valid frame with one byte appended: two byte strings must never mean the same message.
        let mut trailing = golden();
        trailing.push(0);
        assert!(WorldDeletionGossip::decode(&trailing).is_err());
    }
}
