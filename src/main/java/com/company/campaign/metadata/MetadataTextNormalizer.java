package com.company.campaign.metadata;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The single metadata-owned text normalizer, used for duplicate detection and search keys.
 *
 * <p>Applied in a fixed order:
 * <ol>
 *   <li><b>Unicode NFKC</b>, so compatibility-equivalent forms such as full-width latin or circled digits
 *       collapse to one form;</li>
 *   <li><b>trim and collapse Unicode whitespace</b> to a single U+0020, using the Unicode-aware
 *       {@link Character#isWhitespace(int)} and {@link Character#isSpaceChar(int)} predicates rather than
 *       an ASCII regex;</li>
 *   <li><b>locale-independent case folding</b> with {@link Locale#ROOT}, never the process default.</li>
 * </ol>
 *
 * <p>Whitespace collapse works on code points, so a supplementary-plane character is never split. Display
 * text is never replaced by this key: it is computed on demand for collision detection and search, while
 * the original text stays in the immutable definition for output.
 */
public final class MetadataTextNormalizer {

    private MetadataTextNormalizer() {
    }

    /**
     * Normalizes text to its metadata key form.
     *
     * @throws MetadataException with {@link MetadataErrorCodes#METADATA_TEXT_INVALID} when the input is null
     *                           or normalizes to an empty string
     */
    public static String requireNormalized(String text, String field) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            throw new MetadataException(
                    MetadataErrorCodes.METADATA_TEXT_INVALID,
                    "Metadata text '" + field + "' must not be blank");
        }
        return normalized;
    }

    /** Normalizes text, returning an empty string for null/whitespace-only input rather than throwing. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String composed = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder collapsed = new StringBuilder(composed.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < composed.length(); ) {
            int codePoint = composed.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                if (collapsed.length() > 0) {
                    pendingSpace = true;
                }
                continue;
            }
            if (pendingSpace) {
                collapsed.append(' ');
                pendingSpace = false;
            }
            collapsed.appendCodePoint(codePoint);
        }
        return collapsed.toString().toLowerCase(Locale.ROOT);
    }
}