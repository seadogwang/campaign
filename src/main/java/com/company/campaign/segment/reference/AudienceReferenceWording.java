package com.company.campaign.segment.reference;

import java.util.List;
import java.util.Locale;

/**
 * The one deterministic reading of dynamic audience wording such as "当前人群" or "最新人群".
 *
 * <p>A request that names an audience by a moving target must never be resolved into a moving target: the
 * reference contract has no {@code current} or {@code latest} pin at all, so the resolver has to notice the
 * wording and either ask for version semantics or pin one exact immutable version and say so. This class is the
 * single place that decides whether a request text is dynamic, and it reads only the request text itself —
 * never a model-supplied flag, which a caller could simply omit.
 *
 * <p>The reading is deliberately conservative in the safe direction. A text is dynamic when it contains a
 * dynamic qualifier <em>and</em> one of the audience/version head words the request is talking about, so an
 * audience merely named "VIP" is not. An ASCII qualifier additionally has to be a whole word, so a name such
 * as {@code currency-holders} is never mistaken for {@code current}. A false positive costs one clarification
 * or one explicitly disclosed pin; a false negative would silently freeze a moving target, which the security
 * invariants forbid.
 *
 * <p>It is stateless, allocation-free for the common path and echoes nothing: the input text is never attached
 * to a failure or a description.
 */
public final class AudienceReferenceWording {

    /** Qualifiers that make a reference a moving target rather than a name. */
    private static final List<String> QUALIFIERS = List.of(
            "当前", "最新", "现在", "最新版", "current", "latest", "newest");

    /** The head words a dynamic qualifier must be talking about for the request to be about an audience. */
    private static final List<String> HEADS = List.of(
            "人群", "客户群", "会员群", "客群", "分群", "受众",
            "audience", "segment", "version", "版本");

    private AudienceReferenceWording() {
    }

    /** True when the request text names the audience by a moving target rather than by a fixed name. */
    public static boolean isDynamic(String requestText) {
        if (requestText == null) {
            return false;
        }
        String normalized = requestText.toLowerCase(Locale.ROOT);
        boolean aboutAReference = false;
        for (String head : HEADS) {
            if (normalized.contains(head)) {
                aboutAReference = true;
                break;
            }
        }
        if (!aboutAReference) {
            return false;
        }
        for (String qualifier : QUALIFIERS) {
            if (isAscii(qualifier) ? containsWord(normalized, qualifier) : normalized.contains(qualifier)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAscii(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0x7f) {
                return false;
            }
        }
        return true;
    }

    /** True when the ASCII word appears in the text bounded by non-word characters on both sides. */
    private static boolean containsWord(String text, String word) {
        int from = 0;
        while (from <= text.length() - word.length()) {
            int index = text.indexOf(word, from);
            if (index < 0) {
                return false;
            }
            int end = index + word.length();
            boolean leftBounded = index == 0 || !isWordCharacter(text.charAt(index - 1));
            boolean rightBounded = end == text.length() || !isWordCharacter(text.charAt(end));
            if (leftBounded && rightBounded) {
                return true;
            }
            from = index + 1;
        }
        return false;
    }

    private static boolean isWordCharacter(char value) {
        return value >= 'a' && value <= 'z' || value >= '0' && value <= '9' || value == '_';
    }
}