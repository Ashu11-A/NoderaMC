package dev.nodera.storage;

/**
 * A storage-tier failure (Task 9): IO errors, database faults, integrity violations (a blob whose
 * bytes no longer hash to their {@link ContentId}), or a store opened against the wrong world
 * (genesis mismatch). Unchecked because callers on the commit path cannot meaningfully recover
 * in-line — the peer's lifecycle layer decides (retry, resync, or halt).
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
