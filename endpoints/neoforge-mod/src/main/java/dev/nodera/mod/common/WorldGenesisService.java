package dev.nodera.mod.common;

import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.PlayerView;
import dev.nodera.core.region.PlayerViewRegionResolver;
import dev.nodera.core.region.RegionId;
import dev.nodera.mod.server.entity.MinecraftEntityAdapters;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.storage.CertifiedWorldGenesis;
import dev.nodera.storage.SnapshotExtractor;
import dev.nodera.storage.io.AtomicFileWriter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Task 30c: genesis-from-existing-world. On first share this extracts a content digest of the
 * world's currently-relevant regions (the players' field-of-view discs, or the spawn region when no
 * player is online), builds the {@link CertifiedWorldGenesis} — the {@code GenesisManifest} whose
 * root commits to that content — <b>self-certified by the hosting identity</b>, and persists it in
 * the save folder as {@value #FILE_NAME}. Re-opening the save reuses the persisted record, so the
 * world's genesis (and everything derived from it, like the tracker {@code worldId}) is stable.
 *
 * <p><b>Digest fidelity.</b> Task 5b's {@link SnapshotExtractor} is live (L-50): the per-region
 * digest is bit-complete — every loaded section contributes all 4096 block-state ids (properties
 * included), all-air sections and unloaded chunks commit through distinct fixed markers. Genesis
 * stays the one self-signed trust root (L-20).
 *
 * @Thread-context {@link #ensure} must run on the server thread (it reads live chunks).
 */
public final class WorldGenesisService {

    /** The certified-genesis file written into the save's root directory. */
    public static final String FILE_NAME = "nodera-genesis.dat";

    private static final Logger LOG = LoggerFactory.getLogger("NoderaGenesis");

    private WorldGenesisService() {
    }

    /** Resolve the certified-genesis file inside a save's root directory. */
    public static Path fileIn(Path saveRoot) {
        return saveRoot.resolve(FILE_NAME);
    }

    /** @return the persisted record, if present, decodable, and signature-valid. */
    public static Optional<CertifiedWorldGenesis> read(Path saveRoot) {
        Path file = fileIn(saveRoot);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            CertifiedWorldGenesis genesis = CertifiedWorldGenesis.decode(
                    new CanonicalReader(Bytes.unsafeWrap(Files.readAllBytes(file))));
            return genesis.verifySignature() ? Optional.of(genesis) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty(); // corrupt file → re-certify rather than crash the share flow
        }
    }

    /**
     * Load the world's certified genesis, extracting and certifying it on first share.
     *
     * @param server the hosting server (must be on the server thread).
     * @param host   the hosting identity — the genesis signer.
     * @return the certified genesis for this save.
     */
    public static CertifiedWorldGenesis ensure(MinecraftServer server, NodeIdentity host) {
        Path saveRoot = server.getWorldPath(LevelResource.ROOT);
        Optional<CertifiedWorldGenesis> existing = read(saveRoot);
        if (existing.isPresent()) {
            return existing.get();
        }
        ServerLevel overworld = server.overworld();
        Map<RegionId, Bytes> digests = extractDigests(server);
        CertifiedWorldGenesis genesis = CertifiedWorldGenesis.certify(
                overworld.getSeed(), FlatWorldRules.RULES_VERSION,
                FlatWorldRules.registryFingerprint(), digests, host, new HashService());
        try {
            CanonicalWriter w = new CanonicalWriter();
            genesis.encode(w);
            AtomicFileWriter.write(fileIn(saveRoot), w.toBytes().toArray());
        } catch (IOException e) {
            LOG.warn("Nodera: could not persist certified genesis: {}", e.getMessage());
        }
        LOG.info("Nodera: certified world genesis from {} region(s), root {} (author {})",
                digests.size(), genesis.manifest().genesisRoot().toShortHex(4), host.nodeId());
        return genesis;
    }

    /**
     * The regions the genesis commits to: every region any online player activates, or the spawn
     * region when the world is shared before anyone is online (dedicated auto-share boot).
     */
    private static Map<RegionId, Bytes> extractDigests(MinecraftServer server) {
        TreeSet<RegionId> regions = new TreeSet<>((a, b) -> {
            int d = a.dimension().toString().compareTo(b.dimension().toString());
            if (d != 0) {
                return d;
            }
            int z = Integer.compare(a.regionZ(), b.regionZ());
            return z != 0 ? z : Integer.compare(a.regionX(), b.regionX());
        });
        Map<RegionId, ServerLevel> levels = new LinkedHashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();
            PlayerView view = PlayerView.fromBlock(
                    MinecraftEntityAdapters.dimension(level),
                    player.blockPosition().getX(), player.blockPosition().getZ(),
                    server.getPlayerList().getViewDistance());
            for (RegionId region : PlayerViewRegionResolver.activeRegions(view)) {
                regions.add(region);
                levels.putIfAbsent(region, level);
            }
        }
        if (regions.isEmpty()) {
            ServerLevel overworld = server.overworld();
            BlockPos spawn = overworld.getSharedSpawnPos();
            RegionId spawnRegion = RegionId.fromChunk(
                    MinecraftEntityAdapters.dimension(overworld),
                    spawn.getX() >> 4, spawn.getZ() >> 4);
            regions.add(spawnRegion);
            levels.put(spawnRegion, overworld);
        }
        Map<RegionId, Bytes> digests = new LinkedHashMap<>();
        HashService hashes = new HashService();
        for (RegionId region : regions) {
            digests.put(region, regionDigest(levels.get(region), region, hashes));
        }
        return digests;
    }

    /**
     * Bit-complete content digest of one region's loaded chunks (Task 5b, L-50): every section's
     * FULL 4096 block-state ids — properties included via {@code Block.getId(BlockState)} — feed
     * {@link SnapshotExtractor#sectionDigest(int[])}; all-air sections and unloaded chunks commit
     * through distinct fixed markers. Replaces the interim 8-corner coarse sample: an interior
     * edit can no longer alias the digest.
     */
    private static Bytes regionDigest(ServerLevel level, RegionId region, HashService hashes) {
        SnapshotExtractor extractor = new SnapshotExtractor(hashes);
        java.util.List<Bytes> sectionDigests = new java.util.ArrayList<>();
        int originX = region.originChunkX();
        int originZ = region.originChunkZ();
        int[] ids = new int[SnapshotExtractor.SECTION_VOLUME];
        for (int dx = 0; dx < NoderaConstants.REGION_SIZE_CHUNKS; dx++) {
            for (int dz = 0; dz < NoderaConstants.REGION_SIZE_CHUNKS; dz++) {
                LevelChunk chunk = level.getChunkSource()
                        .getChunkNow(originX + dx, originZ + dz);
                if (chunk == null) {
                    sectionDigests.add(extractor.missingChunkMarker());
                    continue;
                }
                for (LevelChunkSection section : chunk.getSections()) {
                    if (section.hasOnlyAir()) {
                        sectionDigests.add(extractor.emptySectionMarker());
                        continue;
                    }
                    int i = 0;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                ids[i++] = net.minecraft.world.level.block.Block.getId(
                                        section.getBlockState(x, y, z));
                            }
                        }
                    }
                    sectionDigests.add(extractor.sectionDigest(ids));
                }
            }
        }
        return extractor.regionDigest(sectionDigests);
    }
}
