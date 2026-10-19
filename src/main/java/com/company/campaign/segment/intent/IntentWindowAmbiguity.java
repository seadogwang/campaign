package com.company.campaign.segment.intent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A structured ambiguity identifying one clause window and its permitted interpretations. */
public record IntentWindowAmbiguity(
        String targetClauseId,
        List<IntentWindowAlternative> options) {

    public IntentWindowAmbiguity {
        targetClauseId = IntentTexts.requireReferenceId(targetClauseId, "windowAmbiguity.targetClauseId");
        if (options == null || options.size() < 2 || options.size() > 8) {
            throw IntentException.invalid(
                    IntentErrorCodes.WINDOW_INVALID,
                    "A window ambiguity requires between two and eight alternatives");
        }
        Set<IntentWindowAlternative> unique = new LinkedHashSet<>();
        for (IntentWindowAlternative option : options) {
            if (option == null || !unique.add(option)) {
                throw IntentException.invalid(
                        IntentErrorCodes.WINDOW_INVALID,
                        "Window ambiguity alternatives must be non-null and unique");
            }
        }
        options = List.copyOf(options);
    }

    public String targetPath() {
        return targetClauseId + ".window";
    }
}
