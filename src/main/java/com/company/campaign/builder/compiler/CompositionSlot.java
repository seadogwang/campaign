package com.company.campaign.builder.compiler;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One composition slot a capability contract occupies.
 *
 * <p>A slot is a frozen fact of a contract version, not a caller-supplied value: a patch cannot lower a
 * composition rule by claiming a different slot. {@link #ordered()} says whether the components in the slot
 * carry a meaningful relative order; {@link #singleton()} says the campaign may hold at most one component
 * in it. The pair is independent, so an unordered singleton and an ordered multi-instance slot are both
 * representable.
 */
public record CompositionSlot(String code, boolean ordered, boolean singleton) {

    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9._-]{0,63}$");

    public CompositionSlot {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("slot code must match " + CODE.pattern());
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CompositionSlot slot
                && ordered == slot.ordered
                && singleton == slot.singleton
                && Objects.equals(code, slot.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, ordered, singleton);
    }
}