package dev.nodera.storage.rocksdb;

import dev.nodera.storage.StorageException;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Open/close of the archival RocksDB instance (Task 9). One database, six column families —
 * {@code default} (meta: genesis), {@code events}, {@code checkpoints}, {@code certificates},
 * {@code regions}, {@code transfers} — with options tuned for the append-mostly workload (WAL on;
 * level compaction).
 * Crash repair is RocksDB's own WAL recovery, performed automatically inside {@link #open}: a
 * kill mid-write replays the WAL to the last complete write on the next open, so the store's
 * append-time invariants (monotonic ids, unbroken chain) are what {@code RocksWorldStore}
 * re-verifies on head recovery — the log, not the process, is the truth.
 *
 * @Thread-context construction/close confined to the owning thread.
 */
final class RocksLifecycle implements AutoCloseable {

    static final String CF_EVENTS = "events";
    static final String CF_CHECKPOINTS = "checkpoints";
    static final String CF_CERTIFICATES = "certificates";
    static final String CF_REGIONS = "regions";
    static final String CF_TRANSFERS = "transfers";

    static {
        RocksDB.loadLibrary();
    }

    private final DBOptions dbOptions;
    private final ColumnFamilyOptions cfOptions;
    private final RocksDB db;
    private final Map<String, ColumnFamilyHandle> handles;

    private RocksLifecycle(DBOptions dbOptions, ColumnFamilyOptions cfOptions, RocksDB db,
                           Map<String, ColumnFamilyHandle> handles) {
        this.dbOptions = dbOptions;
        this.cfOptions = cfOptions;
        this.db = db;
        this.handles = handles;
    }

    /** Open (creating if missing) the database under {@code dir}. WAL recovery runs here. */
    static RocksLifecycle open(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new StorageException("cannot create database directory " + dir, e);
        }
        DBOptions dbOptions = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true);
        ColumnFamilyOptions cfOptions = new ColumnFamilyOptions();
        List<ColumnFamilyDescriptor> descriptors = List.of(
                new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOptions),
                new ColumnFamilyDescriptor(CF_EVENTS.getBytes(StandardCharsets.UTF_8), cfOptions),
                new ColumnFamilyDescriptor(CF_CHECKPOINTS.getBytes(StandardCharsets.UTF_8), cfOptions),
                new ColumnFamilyDescriptor(CF_CERTIFICATES.getBytes(StandardCharsets.UTF_8), cfOptions),
                new ColumnFamilyDescriptor(CF_REGIONS.getBytes(StandardCharsets.UTF_8), cfOptions),
                new ColumnFamilyDescriptor(CF_TRANSFERS.getBytes(StandardCharsets.UTF_8), cfOptions));
        List<ColumnFamilyHandle> opened = new ArrayList<>(descriptors.size());
        RocksDB db;
        try {
            db = RocksDB.open(dbOptions, dir.toString(), descriptors, opened);
        } catch (RocksDBException e) {
            dbOptions.close();
            cfOptions.close();
            throw new StorageException("cannot open RocksDB at " + dir, e);
        }
        Map<String, ColumnFamilyHandle> handles = new LinkedHashMap<>();
        handles.put("default", opened.get(0));
        handles.put(CF_EVENTS, opened.get(1));
        handles.put(CF_CHECKPOINTS, opened.get(2));
        handles.put(CF_CERTIFICATES, opened.get(3));
        handles.put(CF_REGIONS, opened.get(4));
        handles.put(CF_TRANSFERS, opened.get(5));
        return new RocksLifecycle(dbOptions, cfOptions, db, handles);
    }

    RocksDB db() {
        return db;
    }

    ColumnFamilyHandle handle(String name) {
        ColumnFamilyHandle handle = handles.get(name);
        if (handle == null) {
            throw new StorageException("unknown column family: " + name);
        }
        return handle;
    }

    @Override
    public void close() {
        for (ColumnFamilyHandle handle : handles.values()) {
            handle.close();
        }
        db.close();
        dbOptions.close();
        cfOptions.close();
    }
}
