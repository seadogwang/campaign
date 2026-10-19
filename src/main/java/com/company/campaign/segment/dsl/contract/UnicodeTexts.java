package com.company.campaign.segment.dsl.contract;

/**
 * Well-formedness checks for text that becomes part of the editable Segment DSL document.
 *
 * <p>A Java {@code String} can hold an unpaired UTF-16 surrogate, which is not a Unicode scalar value. Encoding
 * such a string to UTF-8 silently substitutes the replacement character, so a stored value would not round-trip
 * and two different Java strings could serialize to the same bytes. Callers use this to refuse the value before
 * it is stored or hashed.
 */
public final class UnicodeTexts {

    private UnicodeTexts() {
    }

    /** True when every code unit is a BMP code point or part of a well-formed surrogate pair. */
    public static boolean isWellFormed(String text) {
        if (text == null) {
            return false;
        }
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }
}