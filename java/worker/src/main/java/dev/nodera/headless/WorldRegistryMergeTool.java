package dev.nodera.headless;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.storage.WorldIdentity;
import dev.nodera.storage.WorldRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The one-shot repair for a registry that already holds duplicate rows for one save (W-DUP-4).
 *
 * <h2>Why this exists</h2>
 *
 * <p>The first pass of Task 8 stopped duplicates <em>growing</em>: a pinned id, a normalised key, a
 * port-free LAN session. It did not repair a registry that already held them. The author's own node
 * had accumulated ten rows for four saves — a save re-shared after a genesis re-certification, a
 * clock jump, or an identity file that could not be written had minted a second id, and both ids
 * stayed in {@code worlds.dat} forever. This tool collapses that history back to one row per save.
 *
 * <h2>The rule the exit test names</h2>
 *
 * <p>"A one-shot merge tool leaves one row per save, with the surviving id chosen by the persisted
 * {@code nodera-world.dat}." Each Minecraft save carries a {@code nodera-world.dat} pinning its
 * canonical world id; that pin is the survivor, and any other id this node minted for the same save
 * is a stale leftover. Concretely:
 *
 * <ul>
 *   <li><b>A pinned id</b> — present in some save's {@code nodera-world.dat} — is <b>kept</b>. It is
 *       the save's name for life.</li>
 *   <li><b>An administered id this node holds a key for, that no save pins</b>, is an orphaned mint
 *       — a duplicate the bug left behind. It is <b>removed</b> (the save now pins a different, 
 *       canonical id). This is the one-row-per-save collapse.</li>
 *   <li><b>A supported world</b> — one this node holds no key for — is <b>kept</b>. The tool has no
 *       authority over a world this node did not create, and removing it would silently drop a peer's
 *       replica.</li>
 *   <li><b>Exact-duplicate rows</b> (one worldId appearing more than once) are collapsed to a single
 *       row, preferring the variant whose ownership record verifies.</li>
 * </ul>
 *
 * <h2>Safe by construction: dry-run and backup-first</h2>
 *
 * <p>The tool never destroys data without consent. {@code --dry-run} reports the plan and writes
 * nothing. A real run copies the original registry to a timestamped {@code .bak} file <b>before</b>
 * writing the merged one, so a wrong call is one file-rename from undone. And anything the tool
 * cannot decide is <b>quarantined</b> — left in place and named in the report — rather than guessed:
 *
 * <ul>
 *   <li>Two saves pinning the same canonical id (the tool cannot say which save owns the row).</li>
 *   <li>An exact-duplicate group whose rows carry <b>conflicting</b> ownership (two different world
 *       public keys for one id) — picking one would be choosing an administrator, which is not a
 *       merge tool's to do.</li>
 *   <li>A registry that cannot be decoded at all — the run aborts rather than overwriting a file it
 *       cannot read.</li>
 * </ul>
 *
 * <p>Orphaned {@code .worldkey} files for removed ids are <b>not</b> deleted: a key is a node's
 * provable authority over a world, and destroying it is not a registry-repair decision. They are
 * harmless once the registry no longer references their id, and leaving them means a rollback of the
 * merge restores authority trivially.
 *
 * <h2>One-shot</h2>
 *
 * <p>The tool is run by hand ({@link #main}) once, against a stopped worker, after which the
 * Task 8 invariants keep the registry duplicate-free on their own. It is not wired into the worker's
 * startup path: a repair that mutates state on every boot is a repair that runs unattended against a
 * file the worker is also writing, and the one-shot model keeps the two apart.
 *
 * @Thread-context blocking IO on the caller's thread; intended to run single-threaded against a
 *                 stopped worker.
 */
public final class WorldRegistryMergeTool {

    private WorldRegistryMergeTool() {
    }

    /** A row the merge removed, with the reason it was judged a duplicate. */
    public record RemovedRow(String worldIdHex, String name, String reason) {
    }

    /** A group of rows the merge left untouched because it could not resolve them safely. */
    public record QuarantinedGroup(String reason, List<String> worldIdHexs) {
    }

    /**
     * The result of one merge run.
     *
     * @param dryRun      whether this was a report-only run.
     * @param registryFile the registry that was (or would have been) repaired.
     * @param rowsBefore  rows in the original registry.
     * @param rowsAfter   rows in the merged registry (equal to {@code rowsBefore} for a dry run that
     *                    changes nothing, and for a real run that found no duplicates).
     * @param kept        the surviving rows, in their original order.
     * @param removed     the duplicate rows removed, each with its reason.
     * @param quarantined the groups the merge could not resolve and left in place.
     * @param backupFile  the backup of the original registry, or {@code null} when nothing was
     *                    written (dry run, or no change).
     */
    public record MergeOutcome(boolean dryRun, Path registryFile, int rowsBefore, int rowsAfter,
                               List<WorldRegistry.Entry> kept, List<RemovedRow> removed,
                               List<QuarantinedGroup> quarantined, Path backupFile) {
    }

    /**
     * Run the merge against {@code registryFile}.
     *
     * @param registryFile    the {@code worlds.dat} to repair; need not exist (an empty registry is
     *                        a no-op).
     * @param savePins        the canonical world id each save pins ({@code saveName → worldIdHex}),
     *                        as read from each save's {@code nodera-world.dat}. The survivor for a
     *                        save is its pin.
     * @param administeredIds the world ids this node holds a private key for (its own worlds); a
     *                        row in this set that no save pins is an orphaned duplicate mint.
     * @param dryRun          {@code true} to report the plan without writing.
     * @return the outcome; never null.
     * @throws UncheckedIOException if the registry cannot be read.
     */
    public static MergeOutcome merge(Path registryFile, Map<String, String> savePins,
                                     Set<String> administeredIds, boolean dryRun) {
        Objects.requireNonNull(registryFile, "registryFile");
        Map<String, String> pins = normalisePins(savePins);
        Set<String> administered = normaliseIds(administeredIds);

        // Which canonical ids are ambiguous (two saves pin the same one)? The tool will not pick a
        // survivor for those: it cannot tell which save the row belongs to.
        Map<String, List<String>> pinClaims = new LinkedHashMap<>();
        for (var e : pins.entrySet()) {
            pinClaims.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        Set<String> ambiguousPins = new LinkedHashSet<>();
        for (var e : pinClaims.entrySet()) {
            if (e.getValue().size() > 1) {
                ambiguousPins.add(e.getKey());
            }
        }

        List<WorldRegistry.Entry> original = readRegistry(registryFile);
        List<RemovedRow> removed = new ArrayList<>();
        List<QuarantinedGroup> quarantined = new ArrayList<>();

        // Group rows by world id (lower-cased) so an exact-duplicate id — the same row written twice
        // — collapses to one, which is the unambiguous form of "duplicate rows for one save".
        Map<String, List<WorldRegistry.Entry>> byId = new LinkedHashMap<>();
        for (WorldRegistry.Entry entry : original) {
            byId.computeIfAbsent(entry.worldIdHex().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(entry);
        }

        List<WorldRegistry.Entry> kept = new ArrayList<>();
        for (var e : byId.entrySet()) {
            String id = e.getKey();
            List<WorldRegistry.Entry> group = e.getValue();

            // Conflicting ownership for one id means two different administrators were recorded for
            // the same world id. Picking one is choosing an administrator, so the group is left.
            if (hasConflictingOwnership(group)) {
                quarantined.add(new QuarantinedGroup(
                        "world " + id + " has rows with conflicting ownership records; refusing to "
                                + "pick an administrator",
                        List.of(id)));
                kept.addAll(group);
                continue;
            }

            // A pinned id that two saves claim is ambiguous: the tool cannot say which save it
            // belongs to, so it keeps the row rather than merging it into the wrong save.
            if (ambiguousPins.contains(id)) {
                quarantined.add(new QuarantinedGroup(
                        "world " + id + " is pinned by more than one save ("
                                + String.join(", ", pinClaims.get(id)) + "); cannot pick a survivor",
                        List.of(id)));
                kept.addAll(group);
                continue;
            }

            WorldRegistry.Entry survivor = collapseToSurvivor(group);

            if (pins.containsValue(id)) {
                // The save's canonical id — the survivor named by its nodera-world.dat.
                kept.add(survivor);
            } else if (administered.contains(id)) {
                // This node minted a key for an id no save pins: a duplicate the bug left behind.
                // The save now pins a different id elsewhere, so this row is removed.
                removed.add(new RemovedRow(id, survivor.name(),
                        "administered locally but pinned by no save — an orphaned duplicate mint"));
            } else {
                // A supported world (no local key) is never the tool's to merge away.
                kept.add(survivor);
            }
        }

        boolean changed = kept.size() != original.size() || rowsDiffer(original, kept);
        Path backupFile = null;
        if (!dryRun && changed) {
            backupFile = backup(registryFile);
            writeRegistry(registryFile, kept);
        }
        int rowsAfter = dryRun ? kept.size() : (changed ? kept.size() : original.size());
        return new MergeOutcome(dryRun, registryFile, original.size(), rowsAfter,
                List.copyOf(kept), List.copyOf(removed), List.copyOf(quarantined), backupFile);
    }

    /**
     * Discover the canonical world id each save pins, by scanning a saves directory for
     * {@code <save>/nodera-world.dat}.
     *
     * @param savesDir the Minecraft {@code saves} directory, or {@code null}/non-existent for none.
     * @return {@code saveName → worldIdHex}; saves whose pin is absent or unreadable are skipped.
     */
    public static Map<String, String> discoverPinnedIds(Path savesDir) {
        Map<String, String> pins = new LinkedHashMap<>();
        if (savesDir == null || !Files.isDirectory(savesDir)) {
            return pins;
        }
        try (Stream<Path> saves = Files.list(savesDir)) {
            List<Path> sorted = new ArrayList<>();
            saves.sorted().forEach(sorted::add);
            for (Path save : sorted) {
                if (!Files.isDirectory(save)) {
                    continue;
                }
                Path pin = save.resolve("nodera-world.dat");
                if (!Files.isRegularFile(pin)) {
                    continue;
                }
                try {
                    WorldIdentity identity =
                            WorldIdentity.decode(new CanonicalReader(Files.readAllBytes(pin)));
                    pins.put(save.getFileName().toString(), identity.worldId().toHex());
                } catch (RuntimeException unreadable) {
                    // An unreadable pin is skipped, not fatal: the tool quarantines only what it can
                    // read, and an operator can re-run once the save is repaired.
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list saves in " + savesDir, e);
        }
        return Collections.unmodifiableMap(pins);
    }

    /**
     * Discover the world ids this node administers, by listing the {@code .worldkey} files.
     *
     * @param worldKeysDir the worker's world-keys directory, or {@code null}/non-existent for none.
     * @return the lower-cased world ids this node holds a private key for.
     */
    public static Set<String> discoverAdministeredIds(Path worldKeysDir) {
        if (worldKeysDir == null || !Files.isDirectory(worldKeysDir)) {
            return Set.of();
        }
        return new LinkedHashSet<>(new WorldKeyStore(worldKeysDir).administeredWorlds());
    }

    /** {@code true} when the two row lists differ in content (order-independent id check). */
    private static boolean rowsDiffer(List<WorldRegistry.Entry> a, List<WorldRegistry.Entry> b) {
        Set<String> ai = new LinkedHashSet<>();
        a.forEach(x -> ai.add(x.worldIdHex().toLowerCase(Locale.ROOT)));
        Set<String> bi = new LinkedHashSet<>();
        b.forEach(x -> bi.add(x.worldIdHex().toLowerCase(Locale.ROOT)));
        return !ai.equals(bi);
    }

    /**
     * Whether a group of rows for one id carries two different verifying world public keys.
     *
     * <p>Empty ownership records do not count as a conflict — a row that lost its claim is not a
     * competing administrator, just an incomplete record. Two distinct non-empty keys, both
     * verifiable, is the ambiguous case the tool refuses to resolve.
     */
    private static boolean hasConflictingOwnership(List<WorldRegistry.Entry> group) {
        Set<String> publicKeys = new LinkedHashSet<>();
        for (WorldRegistry.Entry entry : group) {
            entry.ownership().ifPresent(claim -> publicKeys.add(claim.worldPublicKey().toHex()));
        }
        return publicKeys.size() > 1;
    }

    /**
     * Collapse a same-id group to its survivor row.
     *
     * <p>Prefer a row whose ownership record verifies (the admin binding is intact); otherwise the
     * first row with any ownership record; otherwise the first row. The id, name and supporting flag
     * come from the survivor; the canonical encode/decode round-trip is what gets written.
     */
    private static WorldRegistry.Entry collapseToSurvivor(List<WorldRegistry.Entry> group) {
        WorldRegistry.Entry verifying = null;
        WorldRegistry.Entry withOwnership = null;
        for (WorldRegistry.Entry entry : group) {
            if (entry.ownership().isPresent()) {
                if (verifying == null) {
                    verifying = entry;
                }
            } else if (entry.owned() && withOwnership == null) {
                withOwnership = entry;
            }
        }
        WorldRegistry.Entry survivor = verifying != null ? verifying
                : (withOwnership != null ? withOwnership : group.get(0));
        // Hosting wins over seeding for the survivor, mirroring WorldRegistryStore's own rule: a
        // save this node hosted must not read as merely supported after the merge.
        boolean supporting = true;
        for (WorldRegistry.Entry entry : group) {
            if (!entry.supporting()) {
                supporting = false;
                break;
            }
        }
        if (supporting == survivor.supporting()) {
            return survivor;
        }
        return new WorldRegistry.Entry(survivor.worldId(), survivor.name(), supporting,
                survivor.addedAtEpochMillis(), survivor.updatedAtEpochMillis(),
                survivor.ownershipRecord());
    }

    private static List<WorldRegistry.Entry> readRegistry(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read registry " + file, e);
        }
        try {
            return new ArrayList<>(WorldRegistry.decode(new CanonicalReader(bytes)).entries());
        } catch (RuntimeException malformed) {
            // A registry the tool cannot decode is not overwritten: doing so would destroy whatever
            // an operator could still recover by hand. Abort loudly and let them look.
            throw new IllegalStateException(
                    "registry " + file + " is unreadable (" + malformed.getMessage() + ") — the merge "
                            + "tool refuses to overwrite a file it cannot decode; aborting",
                    malformed);
        }
    }

    private static void writeRegistry(Path file, List<WorldRegistry.Entry> rows) {
        CanonicalWriter w = new CanonicalWriter(512);
        new WorldRegistry(rows).encode(w);
        LocalFiles.writeAtomically(file, w.toByteArray());
    }

    private static Path backup(Path file) {
        Path backup = file.resolveSibling(file.getFileName().toString()
                + ".bak-" + System.currentTimeMillis());
        try {
            Files.copy(file, backup);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to back up " + file + " to " + backup, e);
        }
        return backup;
    }

    private static Map<String, String> normalisePins(Map<String, String> savePins) {
        Map<String, String> out = new LinkedHashMap<>();
        if (savePins == null) {
            return out;
        }
        for (var e : savePins.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) {
                out.put(e.getKey(), e.getValue().trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static Set<String> normaliseIds(Set<String> ids) {
        Set<String> out = new LinkedHashSet<>();
        if (ids == null) {
            return out;
        }
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                out.add(id.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /**
     * Run the merge as a one-shot CLI. Run it against a <b>stopped</b> worker.
     *
     * <pre>
     *   WorldRegistryMergeTool [--registry &lt;worlds.dat&gt;] [--saves &lt;saves-dir&gt;]
     *                          [--world-keys &lt;world-keys-dir&gt;] [--dry-run]
     * </pre>
     *
     * <p>Defaults follow the worker's own: {@code ~/.nodera/worlds.dat},
     * {@code ~/.nodera/world-keys}. The saves directory has no worker default (the worker does not
     * know where Minecraft stores its saves), so pass {@code --saves} explicitly.
     *
     * @param args the arguments above.
     */
    public static void main(String[] args) {
        Path registry = Path.of(System.getProperty("user.home"), ".nodera", "worlds.dat");
        Path savesDir = null;
        Path worldKeys = Path.of(System.getProperty("user.home"), ".nodera", "world-keys");
        boolean dryRun = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--registry" -> registry = Path.of(args[++i]);
                case "--saves" -> savesDir = Path.of(args[++i]);
                case "--world-keys" -> worldKeys = Path.of(args[++i]);
                case "--dry-run" -> dryRun = true;
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    System.exit(2);
                }
            }
        }
        Map<String, String> pins = discoverPinnedIds(savesDir);
        Set<String> administered = discoverAdministeredIds(worldKeys);
        System.out.println((dryRun ? "[dry-run] " : "") + "merging " + registry);
        System.out.println("  saves pinned: " + pins.size() + " — " + pins);
        System.out.println("  administered: " + administered.size() + " — " + administered);

        MergeOutcome outcome;
        try {
            outcome = merge(registry, pins, administered, dryRun);
        } catch (RuntimeException e) {
            System.err.println("merge aborted: " + e.getMessage());
            System.exit(1);
            return;
        }
        System.out.println("  rows: " + outcome.rowsBefore() + " -> " + outcome.rowsAfter());
        for (RemovedRow r : outcome.removed()) {
            System.out.println("  remove " + r.worldIdHex() + " (" + r.name() + "): " + r.reason());
        }
        for (QuarantinedGroup q : outcome.quarantined()) {
            System.out.println("  QUARANTINE " + q.worldIdHexs() + ": " + q.reason());
        }
        if (outcome.backupFile() != null) {
            System.out.println("  backup written to " + outcome.backupFile());
        }
        if (!dryRun && outcome.backupFile() == null && outcome.rowsBefore() == outcome.rowsAfter()) {
            System.out.println("  no duplicates found; registry unchanged");
        }
    }
}
