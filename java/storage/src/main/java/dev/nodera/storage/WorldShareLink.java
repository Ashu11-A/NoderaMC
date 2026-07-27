package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything a stranger needs to reach one world, in a form you can paste into a chat window.
 *
 * <h2>Why this is shaped like a magnet link</h2>
 *
 * <p>A world on this network is identified by a hash and located through trackers and rendezvous
 * services — which is, structurally, exactly what a magnet link carries for a torrent. Borrowing the
 * shape is not decoration: people already know that a link is the whole invitation, that it contains
 * no content, and that receiving one does not mean the sender has to stay online while you decide.
 * All three are true here.
 *
 * <p>Two encodings, one record. The <b>URI</b> ({@link #toUri()}) is what you paste; the
 * <b>file</b> ({@link #encode}) is the canonical byte form, for saving a {@code .nodera} invitation
 * or for signing one later. Neither is derived from the other at use time — both come from the same
 * fields, so they cannot drift.
 *
 * <h2>What it deliberately does not contain</h2>
 *
 * <p>No world data, no save, no piece. A link is an address and a set of places to ask; it is not a
 * copy of anything, and a LAN invitation in particular carries no content at all because there is no
 * content to carry — only a live connection to somebody's running game.
 *
 * <p>It also carries no secret. A password-protected world's link gets you as far as being asked for
 * the password, which is the correct amount of access for something people forward.
 *
 * <p>Wire form: {@code [u16 WORLD_SHARE_LINK][u16 version][Bytes worldId][String name][String kind]
 * [list String trackers][list String rendezvous][Bytes worldPublicKey][String hostNodeId]
 * [u64 createdAtEpoch]}.
 *
 * @param worldId        the world's identity — the {@code xt} of the link.
 * @param name           its display name, for a human reading the link.
 * @param kind           {@link #KIND_WORLD} for a shared world, {@link #KIND_LAN} for a live
 *                       "Open to LAN" session.
 * @param trackers       where to ask who has it.
 * @param rendezvous     where to ask how to reach them through a router.
 * @param worldPublicKey the world's administrative public key, or empty when unknown.
 * @param hostNodeId     the peer that published it, or empty — a hint that saves a lookup, never a
 *                       requirement, because the whole point of the trackers is that the host may
 *                       have moved.
 * @param createdAtEpoch when the link was minted.
 * @Thread-context immutable record, safe for any thread.
 */
public record WorldShareLink(
        Bytes worldId,
        String name,
        String kind,
        List<String> trackers,
        List<String> rendezvous,
        Bytes worldPublicKey,
        String hostNodeId,
        long createdAtEpoch) implements Encodable {

    /** A world whose content lives on the network. */
    public static final String KIND_WORLD = "world";

    /** A live "Open to LAN" session: a connection, with no content behind it. */
    public static final String KIND_LAN = "lan";

    /** The URI scheme this app registers. */
    public static final String SCHEME = "nodera";

    /** The URN namespace inside the link's {@code xt}, mirroring {@code urn:btih:} for torrents. */
    public static final String URN_PREFIX = "urn:nodera:";

    public WorldShareLink {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(worldPublicKey, "worldPublicKey");
        name = name == null ? "" : name;
        kind = kind == null || kind.isBlank() ? KIND_WORLD : kind.trim().toLowerCase(Locale.ROOT);
        hostNodeId = hostNodeId == null ? "" : hostNodeId;
        trackers = List.copyOf(Objects.requireNonNullElse(trackers, List.of()));
        rendezvous = List.copyOf(Objects.requireNonNullElse(rendezvous, List.of()));
        if (worldId.isEmpty()) {
            throw new IllegalArgumentException("a share link must name a world");
        }
    }

    /** @return whether this invitation is to a live LAN session rather than to stored content. */
    public boolean isLan() {
        return KIND_LAN.equals(kind);
    }

    /**
     * Render the pasteable form.
     *
     * <p>Parameter names follow magnet convention where one exists — {@code xt} for the identity,
     * {@code dn} for the display name, {@code tr} for trackers — so the link is legible to anyone
     * who has seen a torrent link, and so a future tool can guess right.
     *
     * @return the {@code nodera:?…} URI.
     */
    public String toUri() {
        StringBuilder uri = new StringBuilder(SCHEME).append(":?xt=")
                .append(encode(URN_PREFIX + worldId.toHex()));
        if (!name.isEmpty()) {
            uri.append("&dn=").append(encode(name));
        }
        uri.append("&kind=").append(encode(kind));
        for (String tracker : trackers) {
            uri.append("&tr=").append(encode(tracker));
        }
        for (String service : rendezvous) {
            uri.append("&rz=").append(encode(service));
        }
        if (!worldPublicKey.isEmpty()) {
            uri.append("&pk=").append(encode(
                    java.util.Base64.getEncoder().encodeToString(worldPublicKey.toArray())));
        }
        if (!hostNodeId.isEmpty()) {
            uri.append("&host=").append(encode(hostNodeId));
        }
        return uri.toString();
    }

    /**
     * Parse a pasteable link.
     *
     * <p>Accepts the {@code magnet:?…} spelling as well as {@code nodera:?…}: people paste what
     * their client turned the text into, and refusing a link because of its scheme when every other
     * field is present would be pedantry with a support cost.
     *
     * @param uri the text.
     * @return the link, or empty when the text is not one.
     * @Thread-context any thread.
     */
    public static Optional<WorldShareLink> parse(String uri) {
        if (uri == null) {
            return Optional.empty();
        }
        String text = uri.trim();
        int query = text.indexOf('?');
        if (query < 0) {
            return Optional.empty();
        }
        String scheme = text.substring(0, query).replace(":", "").toLowerCase(Locale.ROOT);
        if (!scheme.equals(SCHEME) && !scheme.equals("magnet")) {
            return Optional.empty();
        }
        Map<String, List<String>> params = new LinkedHashMap<>();
        for (String pair : text.substring(query + 1).split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : decode(pair.substring(equals + 1));
            params.computeIfAbsent(key.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(value);
        }
        String xt = first(params, "xt");
        if (xt == null || !xt.startsWith(URN_PREFIX)) {
            return Optional.empty();
        }
        Bytes worldId;
        try {
            worldId = Bytes.fromHex(xt.substring(URN_PREFIX.length()));
        } catch (RuntimeException notHex) {
            return Optional.empty();
        }
        if (worldId.isEmpty()) {
            return Optional.empty();
        }
        Bytes publicKey = Bytes.empty();
        String pk = first(params, "pk");
        if (pk != null && !pk.isBlank()) {
            try {
                publicKey = Bytes.unsafeWrap(java.util.Base64.getDecoder().decode(pk));
            } catch (IllegalArgumentException notBase64) {
                // A malformed key costs the admin badge, not the invitation: the link's job is to
                // get you to the world, and the key is verified against the network anyway.
                publicKey = Bytes.empty();
            }
        }
        return Optional.of(new WorldShareLink(
                worldId,
                Objects.requireNonNullElse(first(params, "dn"), ""),
                Objects.requireNonNullElse(first(params, "kind"), KIND_WORLD),
                params.getOrDefault("tr", List.of()),
                params.getOrDefault("rz", List.of()),
                publicKey,
                Objects.requireNonNullElse(first(params, "host"), ""),
                System.currentTimeMillis()));
    }

    private static String first(Map<String, List<String>> params, String key) {
        List<String> values = params.get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException | IllegalArgumentException malformed) {
            return value;
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.WORLD_SHARE_LINK).writeU16(ENCODING_VERSION);
        w.writeBytes(worldId);
        w.writeString(name);
        w.writeString(kind);
        w.writeList(trackers, CanonicalWriter::writeString);
        w.writeList(rendezvous, CanonicalWriter::writeString);
        w.writeBytes(worldPublicKey);
        w.writeString(hostNodeId);
        w.writeU64(createdAtEpoch);
    }

    /**
     * Full-frame decode of the {@code .nodera} file form.
     *
     * @param r the reader positioned at this value's tag.
     * @return the decoded link.
     * @throws IllegalStateException if the next tag is not {@code WORLD_SHARE_LINK}.
     */
    public static WorldShareLink decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.WORLD_SHARE_LINK) {
            throw new IllegalStateException("expected WORLD_SHARE_LINK tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        Bytes worldId = r.readBytesValue();
        String name = r.readString();
        String kind = r.readString();
        List<String> trackers = r.readList(CanonicalReader::readString);
        List<String> rendezvous = r.readList(CanonicalReader::readString);
        Bytes publicKey = r.readBytesValue();
        String host = r.readString();
        long createdAt = r.readU64();
        return new WorldShareLink(worldId, name, kind, trackers, rendezvous, publicKey, host,
                createdAt);
    }
}
