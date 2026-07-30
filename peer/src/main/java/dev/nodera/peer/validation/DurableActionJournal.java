package dev.nodera.peer.validation;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
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
import dev.nodera.core.identity.NodeId;

/** Atomic-file action reservation journal; restart cannot reopen consumed sequence numbers. */
public final class DurableActionJournal implements ActionReservationPersistence {

    private static final long MAGIC = 0x4E414354L; // NACT
    private static final int VERSION = 1;

    private enum Stage { RESERVED, COMMITTED, ABORTED }

    private final Path file;
    private final Map<Key, Entry> entries = new LinkedHashMap<>();

    public DurableActionJournal(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        this.file = file;
        load();
    }

    @Override
    public synchronized void reserve(List<ActionEnvelope> actions) {
        transition(actions, Stage.RESERVED);
    }

    @Override
    public synchronized void commit(List<ActionEnvelope> actions) {
        transition(actions, Stage.COMMITTED);
    }

    @Override
    public synchronized void abort(List<ActionEnvelope> actions) {
        transition(actions, Stage.ABORTED);
    }

    @Override
    public synchronized List<ActionEnvelope> retained() {
        return entries.values().stream().map(Entry::action).toList();
    }

    /** Non-terminal actions that need host reconciliation after restart. */
    public synchronized List<ActionEnvelope> pending() {
        return entries.values().stream()
                .filter(entry -> entry.stage == Stage.RESERVED)
                .map(Entry::action)
                .toList();
    }

    /**
     * Compensate every non-terminal reservation after a restart. A RESERVED entry means the
     * process died between reserving a batch and recording its outcome; the journal is reservation
     * bookkeeping only — certified state lives in the world store and is recovered from there — so
     * the safe reconciliation is to abort the reservation. The entry stays in the journal as
     * ABORTED: its sequence numbers remain consumed ({@link #retained()} feeds the restart
     * watermarks), so a compensated action can never be replayed or its sequence reused.
     *
     * @return the compensated actions (empty when the shutdown was clean).
     */
    public synchronized List<ActionEnvelope> abortPending() {
        List<ActionEnvelope> stale = pending();
        if (!stale.isEmpty()) {
            abort(stale);
        }
        return stale;
    }

    /** Next globally monotonic server sequence after all retained terminal/pending actions. */
    public synchronized long nextServerSequence() {
        return entries.values().stream().mapToLong(entry -> entry.action.serverSeq())
                .max().orElse(-1L) + 1L;
    }

    /** Next monotonic player sequence for one authenticated actor. */
    public synchronized long nextPlayerSequence(NodeId actor) {
        return entries.values().stream()
                .map(Entry::action)
                .filter(action -> action.actor().equals(actor))
                .mapToLong(ActionEnvelope::playerSeq)
                .max().orElse(-1L) + 1L;
    }

    private void transition(List<ActionEnvelope> actions, Stage target) {
        if (actions == null) {
            throw new IllegalArgumentException("actions must not be null");
        }
        Map<Key, Entry> next = new LinkedHashMap<>(entries);
        for (ActionEnvelope action : actions) {
            if (action == null) {
                throw new IllegalArgumentException("action must not be null");
            }
            Key key = new Key(action.actor().value().toString(), action.playerSeq(), action.serverSeq());
            Entry current = next.get(key);
            if (target == Stage.RESERVED) {
                if (current != null && !current.action.equals(action)) {
                    throw new IllegalArgumentException("action sequence reused with different payload");
                }
                next.putIfAbsent(key, new Entry(Stage.RESERVED, action));
                continue;
            }
            if (current == null || !current.action.equals(action)) {
                throw new IllegalStateException("cannot finish an action that was not reserved");
            }
            if (current.stage != Stage.RESERVED && current.stage != target) {
                throw new IllegalStateException("action already finished as " + current.stage);
            }
            next.put(key, new Entry(target, action));
        }
        persist(next);
        entries.clear();
        entries.putAll(next);
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            CanonicalReader reader = new CanonicalReader(Files.readAllBytes(file));
            if (reader.readU32() != MAGIC || reader.readU16() != VERSION) {
                throw new StorageException("unsupported action journal header: " + file);
            }
            for (Entry entry : reader.readList(r -> {
                int ordinal = r.readU8();
                if (ordinal >= Stage.values().length) {
                    throw new StorageException("unknown action journal stage " + ordinal);
                }
                return new Entry(Stage.values()[ordinal], ActionEnvelope.decode(r));
            })) {
                Key key = new Key(entry.action.actor().value().toString(),
                        entry.action.playerSeq(), entry.action.serverSeq());
                if (entries.putIfAbsent(key, entry) != null) {
                    throw new StorageException("duplicate action journal key " + key);
                }
            }
            if (reader.available() != 0) {
                throw new StorageException("trailing bytes in action journal " + file);
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof StorageException storage) {
                throw storage;
            }
            throw new StorageException("cannot load action journal " + file, e);
        }
    }

    private void persist(Map<Key, Entry> next) {
        List<Entry> ordered = new ArrayList<>(next.values());
        ordered.sort(Comparator
                .comparingLong((Entry entry) -> entry.action.serverSeq())
                .thenComparing(entry -> entry.action.actor().value())
                .thenComparingLong(entry -> entry.action.playerSeq()));
        CanonicalWriter writer = new CanonicalWriter();
        writer.writeU32(MAGIC).writeU16(VERSION);
        writer.writeList(ordered, (w, entry) -> {
            w.writeU8(entry.stage.ordinal());
            entry.action.encode(w);
        });
        try {
            AtomicFileWriter.write(file, writer.toByteArray());
        } catch (IOException e) {
            throw new StorageException("cannot persist action journal " + file, e);
        }
    }

    private record Key(String actor, long playerSeq, long serverSeq) {
    }

    private record Entry(Stage stage, ActionEnvelope action) {
    }
}
