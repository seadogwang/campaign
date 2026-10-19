package com.company.campaign.readiness;

import java.util.ArrayList;
import java.util.List;

/**
 * The three ordered readiness levels.
 *
 * <p>The order is the contract: {@link #AI_READY} includes every {@link #LOCAL_READY} requirement and
 * {@link #LIVE_READY} includes every {@link #LOCAL_READY} and {@link #AI_READY} requirement. A report for a
 * higher level therefore cannot skip a lower one — {@link ReadinessCatalog#requirementsFor(ReadinessLevel)}
 * is derived from this order rather than restated per level.
 *
 * <p>The enum name is the wire value; no aliases are accepted, so one level has exactly one spelling.
 */
public enum ReadinessLevel {

    LOCAL_READY,
    AI_READY,
    LIVE_READY;

    /** True when satisfying this level requires every requirement introduced at {@code other}. */
    public boolean includes(ReadinessLevel other) {
        return other != null && other.ordinal() <= ordinal();
    }

    /** Every level up to and including this one, in ascending order. */
    public List<ReadinessLevel> levelsUpTo() {
        return List.of(values()).subList(0, ordinal() + 1);
    }

    /** Every level at which a requirement introduced at this level is required, in ascending order. */
    public List<ReadinessLevel> levelsFrom() {
        List<ReadinessLevel> levels = new ArrayList<>(values().length - ordinal());
        for (int index = ordinal(); index < values().length; index++) {
            levels.add(values()[index]);
        }
        return List.copyOf(levels);
    }
}