package dev.nodera.peer.validation;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.state.InventoryCredit;
import dev.nodera.storage.StorageException;
import dev.nodera.storage.io.AtomicFileWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Atomic-file retained-credit outbox; player attachments provide delivery idempotency. */
public final class DurableInventoryCreditJournal implements InventoryCreditPersistence {

    private static final long MAGIC = 0x4E435244L; // NCRD
    private static final int VERSION = 1;

    private final Path file;
    private final Map<Key, InventoryCredit> credits = new LinkedHashMap<>();

    public DurableInventoryCreditJournal(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        this.file = file;
        load();
    }

    @Override
    public synchronized void retain(InventoryCredit credit) {
        if (credit == null) {
            throw new IllegalArgumentException("credit must not be null");
        }
        Key key = Key.of(credit);
        InventoryCredit current = credits.get(key);
        if (current != null) {
            if (!current.equals(credit)) {
                throw new IllegalArgumentException("inventory credit key reused with different payload");
            }
            return;
        }
        Map<Key, InventoryCredit> next = new LinkedHashMap<>(credits);
        next.put(key, credit);
        persist(next);
        credits.put(key, credit);
    }

    @Override
    public synchronized List<InventoryCredit> retained() {
        return ordered(credits);
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            CanonicalReader reader = new CanonicalReader(Files.readAllBytes(file));
            if (reader.readU32() != MAGIC || reader.readU16() != VERSION) {
                throw new StorageException("unsupported inventory-credit journal header: " + file);
            }
            for (InventoryCredit credit : reader.readList(InventoryCredit::decode)) {
                Key key = Key.of(credit);
                if (credits.putIfAbsent(key, credit) != null) {
                    throw new StorageException("duplicate inventory-credit journal key " + key);
                }
            }
            if (reader.available() != 0) {
                throw new StorageException("trailing bytes in inventory-credit journal " + file);
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof StorageException storage) {
                throw storage;
            }
            throw new StorageException("cannot load inventory-credit journal " + file, e);
        }
    }

    private void persist(Map<Key, InventoryCredit> next) {
        CanonicalWriter writer = new CanonicalWriter();
        writer.writeU32(MAGIC).writeU16(VERSION);
        writer.writeList(ordered(next), (w, credit) -> credit.encode(w));
        try {
            AtomicFileWriter.write(file, writer.toByteArray());
        } catch (IOException e) {
            throw new StorageException("cannot persist inventory-credit journal " + file, e);
        }
    }

    private static List<InventoryCredit> ordered(Map<Key, InventoryCredit> values) {
        List<InventoryCredit> ordered = new ArrayList<>(values.values());
        ordered.sort(Comparator.comparing((InventoryCredit credit) -> credit.actor().value())
                .thenComparing(InventoryCredit::entityId));
        return List.copyOf(ordered);
    }

    private record Key(String actor, long entityId) {
        static Key of(InventoryCredit credit) {
            return new Key(credit.actor().value().toString(), credit.entityId().value());
        }
    }
}
