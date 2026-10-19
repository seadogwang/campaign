package com.company.campaign.segment.intent;

/** One bounded candidate interpretation for an ambiguous Metric time window. */
public sealed interface IntentWindowAlternative
        permits IntentWindowAlternative.InheritWindow, IntentWindowAlternative.ExplicitWindow {

    /** Reuses the exact window already carried by another clause in the same Intent. */
    record InheritWindow(String fromClauseId) implements IntentWindowAlternative {
        public InheritWindow {
            fromClauseId = IntentTexts.requireReferenceId(fromClauseId, "windowAlternative.fromClauseId");
        }
    }

    /** Uses one fully specified legal Intent window. */
    record ExplicitWindow(IntentWindow window) implements IntentWindowAlternative {
        public ExplicitWindow {
            if (window == null) {
                throw IntentException.invalid(
                        IntentErrorCodes.WINDOW_INVALID, "A window alternative requires a window");
            }
        }
    }
}
