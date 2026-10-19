package com.company.campaign.contracts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Exact character range in the original user text that supports one clause or issue.
 *
 * <p>The range is half-open: {@code [start, end)}. {@code start} and {@code end} count UTF-16 code units
 * of the original text, so {@code {"text":"Gold会员","start":0,"end":6}} is a full match and an empty
 * range ({@code start == end}) is legal and means "no user text supports this", which callers must pair
 * with a registered default source.
 */
public record SourceSpan(
        @NotNull String text,
        @Min(0) int start,
        @Min(0) int end
) {
    public SourceSpan {
        if (end < start) {
            throw new IllegalArgumentException("Source span end " + end + " must not precede start " + start);
        }
    }

    /**
     * True when the range is empty and therefore carries no user text.
     *
     * <p>This is a derived predicate, not part of the span. Without {@link JsonIgnore} Jackson would treat
     * {@code isEmpty()} as a boolean getter and write a synthetic {@code "empty"} property into every
     * serialized span, which both changes the wire shape and makes the span fail to read back under
     * unknown-field rejection.
     */
    @JsonIgnore
    public boolean isEmpty() {
        return start == end;
    }
}