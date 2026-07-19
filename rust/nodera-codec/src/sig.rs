//! Ed25519 **verification** over canonical bytes.
//!
//! The services verify; they never sign. Signing stays a Java-peer capability (`NodeIdentity`), so
//! no service process ever holds peer signing material — a compromised tracker or relay can lie
//! about who it saw, never forge a record (Task 0 §4 rule 7).

use ed25519_dalek::{Signature, Verifier, VerifyingKey};

/// Why a signature check failed. Deliberately coarse: callers log a rejection and count a quota
/// strike, they never branch on the detail.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum SignatureError {
    /// The public key was not 32 bytes, or not a valid compressed Edwards point.
    #[error("malformed Ed25519 public key")]
    BadPublicKey,
    /// The signature was not 64 bytes.
    #[error("malformed Ed25519 signature (expected 64 bytes, got {0})")]
    BadSignatureLength(usize),
    /// The signature did not verify against the message and key.
    #[error("Ed25519 signature verification failed")]
    Rejected,
}

/// Verify `signature` over `message` under `public_key`.
///
/// `message` must be the exact canonical bytes the signer covered — never a re-encoding produced
/// from a decoded value, which is why callers keep the received slice around.
pub fn verify(public_key: &[u8], message: &[u8], signature: &[u8]) -> Result<(), SignatureError> {
    let key_bytes: [u8; 32] = public_key
        .try_into()
        .map_err(|_| SignatureError::BadPublicKey)?;
    let key = VerifyingKey::from_bytes(&key_bytes).map_err(|_| SignatureError::BadPublicKey)?;
    let sig_bytes: [u8; 64] = signature
        .try_into()
        .map_err(|_| SignatureError::BadSignatureLength(signature.len()))?;
    let sig = Signature::from_bytes(&sig_bytes);
    key.verify(message, &sig)
        .map_err(|_| SignatureError::Rejected)
}

#[cfg(test)]
mod tests {
    use super::*;

    // A known-answer vector from RFC 8032 §7.1 (TEST 1) — no signing key is needed to test the
    // verify path, which is exactly the capability boundary this module enforces.
    const PUBLIC_KEY: [u8; 32] = [
        0xd7, 0x5a, 0x98, 0x01, 0x82, 0xb1, 0x0a, 0xb7, 0xd5, 0x4b, 0xfe, 0xd3, 0xc9, 0x64, 0x07,
        0x3a, 0x0e, 0xe1, 0x72, 0xf3, 0xda, 0xa6, 0x23, 0x25, 0xaf, 0x02, 0x1a, 0x68, 0xf7, 0x07,
        0x51, 0x1a,
    ];
    const SIGNATURE: [u8; 64] = [
        0xe5, 0x56, 0x43, 0x00, 0xc3, 0x60, 0xac, 0x72, 0x90, 0x86, 0xe2, 0xcc, 0x80, 0x6e, 0x82,
        0x8a, 0x84, 0x87, 0x7f, 0x1e, 0xb8, 0xe5, 0xd9, 0x74, 0xd8, 0x73, 0xe0, 0x65, 0x22, 0x49,
        0x01, 0x55, 0x5f, 0xb8, 0x82, 0x15, 0x90, 0xa3, 0x3b, 0xac, 0xc6, 0x1e, 0x39, 0x70, 0x1c,
        0xf9, 0xb4, 0x6b, 0xd2, 0x5b, 0xf5, 0xf0, 0x59, 0x5b, 0xbe, 0x24, 0x65, 0x51, 0x41, 0x43,
        0x8e, 0x7a, 0x10, 0x0b,
    ];

    #[test]
    fn valid_signature_verifies() {
        verify(&PUBLIC_KEY, b"", &SIGNATURE).unwrap();
    }

    #[test]
    fn tampered_message_is_rejected() {
        assert_eq!(
            verify(&PUBLIC_KEY, b"tampered", &SIGNATURE),
            Err(SignatureError::Rejected)
        );
    }

    #[test]
    fn malformed_inputs_are_rejected_without_panicking() {
        assert_eq!(
            verify(&[0u8; 8], b"", &SIGNATURE),
            Err(SignatureError::BadPublicKey)
        );
        assert_eq!(
            verify(&PUBLIC_KEY, b"", &[0u8; 3]),
            Err(SignatureError::BadSignatureLength(3))
        );
    }
}
