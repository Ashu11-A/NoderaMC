package dev.nodera.headless;

import java.util.Locale;

/**
 * The two things every service in this package does to a world id before it uses it.
 *
 * <p>Both were written seven and eight times respectively, once per service, as identical private
 * statics. They are not interesting enough to be interesting twice — but they <em>are</em> load
 * bearing: {@link #key} is what makes {@code ABC…} and {@code abc…} the same world in every map in
 * this package, and a copy that forgot the {@link Locale#ROOT} would turn a Turkish locale's dotless
 * i into a second world with the same name.
 */
final class WorldIds {

    private WorldIds() {
    }

    /**
     * The canonical map key for a world id: trimmed and lower-cased in the root locale.
     *
     * @param hex a world id as it arrived, possibly padded or upper-cased.
     * @return the form every store in this package keys by.
     */
    static String key(String hex) {
        return hex.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The first twelve characters, for a log line.
     *
     * <p>Never for a comparison: two worlds sharing a prefix are different worlds, and this is a
     * label rather than an identifier.
     *
     * @param hex a world id.
     * @return enough of it to recognise in a log.
     */
    static String shortId(String hex) {
        return hex.length() <= 12 ? hex : hex.substring(0, 12);
    }
}
