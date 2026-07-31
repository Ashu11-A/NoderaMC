package dev.nodera.endpoint.state;

import dev.nodera.peer.control.ControlProtocol;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Base64;
import java.util.List;

/**
 * Reader for the worker's {@code NODERA-PIECES} reply — the per-world piece picture behind the
 * client's torrent-style piece map.
 *
 * <p>Same discipline as {@link WorkerStateParser}: a tiny hand-rolled scanner rather than a JSON
 * library on the mod classpath, and anything it cannot make sense of yields an empty result instead
 * of throwing, because a malformed worker reply must never crash a screen the player is looking at.
 *
 * <p>Thread-context: stateless static functions; any thread.
 */
public final class WorkerPiecesParser {

    /**
     * One world's piece picture as the worker reports it.
     *
     * @param worldId      the world, hex-encoded.
     * @param manifestRoot the content checksum identifying these exact bytes.
     * @param version      the manifest version this report describes.
     * @param pieceCount   total pieces.
     * @param heldCount    pieces verified present on this machine.
     * @param totalBytes   total content length.
     * @param held         bit per piece: set = verified present locally.
     * @param holders      peers believed to hold any of this world.
     */
    public record PieceInfo(String worldId, String manifestRoot, long version, int pieceCount,
                            int heldCount, long totalBytes, BitSet held, List<String> holders) {

        /** The empty report: a world this node knows no manifest for. */
        public static PieceInfo empty() {
            return new PieceInfo("", "", 0, 0, 0, 0, new BitSet(), List.of());
        }

        /** @return {@code true} when there is nothing to draw. */
        public boolean isEmpty() {
            return pieceCount <= 0;
        }
    }

    private WorkerPiecesParser() {
    }

    /**
     * Parse a {@code NODERA-PIECES} reply.
     *
     * @param json the reply line, or {@code null}.
     * @return the report; {@link PieceInfo#empty()} if absent, an error line, or malformed.
     */
    public static PieceInfo parse(String json) {
        if (json == null || json.isBlank() || json.startsWith(ControlProtocol.ERR)) {
            return PieceInfo.empty();
        }
        int pieceCount = (int) Math.max(0, longField(json, "piece_count"));
        if (pieceCount <= 0) {
            return PieceInfo.empty();
        }
        BitSet held = decodeBitmap(stringField(json, "held_bitmap"));
        return new PieceInfo(
                nullToEmpty(stringField(json, "world_id")),
                nullToEmpty(stringField(json, "manifest_root")),
                longField(json, "version"),
                pieceCount,
                (int) Math.max(0, longField(json, "held_count")),
                longField(json, "total_bytes"),
                held,
                stringArrayField(json, "holders"));
    }

    /**
     * Decode the base64 {@link BitSet#toByteArray()} form the worker sends. A bitmap we cannot
     * decode reads as "nothing held" rather than as an exception: an all-grey map is a truthful
     * rendering of "we do not know what is here", whereas a crash is not.
     */
    private static BitSet decodeBitmap(String base64) {
        if (base64 == null || base64.isBlank()) {
            return new BitSet();
        }
        try {
            return BitSet.valueOf(Base64.getDecoder().decode(base64));
        } catch (IllegalArgumentException e) {
            return new BitSet();
        }
    }

    /** Read a JSON string field from a flat object; {@code null} when absent. */
    private static String stringField(String json, String key) {
        int at = json.indexOf("\"" + key + "\"");
        if (at < 0) {
            return null;
        }
        int colon = json.indexOf(':', at + key.length() + 2);
        if (colon < 0) {
            return null;
        }
        int open = json.indexOf('"', colon + 1);
        if (open < 0) {
            return null;
        }
        int close = json.indexOf('"', open + 1);
        return close < 0 ? null : json.substring(open + 1, close);
    }

    /** Read a JSON integer field from a flat object; {@code 0} when absent or unparsable. */
    private static long longField(String json, String key) {
        int at = json.indexOf("\"" + key + "\"");
        if (at < 0) {
            return 0;
        }
        int i = json.indexOf(':', at + key.length() + 2);
        if (i < 0) {
            return 0;
        }
        i++;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '-')) {
            i++;
        }
        if (i == start) {
            return 0;
        }
        try {
            return Long.parseLong(json.substring(start, i));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Read a JSON array of strings; empty when absent or malformed. */
    private static List<String> stringArrayField(String json, String key) {
        List<String> out = new ArrayList<>();
        int at = json.indexOf("\"" + key + "\"");
        if (at < 0) {
            return out;
        }
        int open = json.indexOf('[', at);
        int close = open < 0 ? -1 : json.indexOf(']', open);
        if (open < 0 || close < 0) {
            return out;
        }
        int i = open + 1;
        while (i < close) {
            int quote = json.indexOf('"', i);
            if (quote < 0 || quote > close) {
                break;
            }
            int end = json.indexOf('"', quote + 1);
            if (end < 0 || end > close) {
                break;
            }
            out.add(json.substring(quote + 1, end));
            i = end + 1;
        }
        return out;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
