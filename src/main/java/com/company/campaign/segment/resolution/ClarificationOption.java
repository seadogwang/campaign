package com.company.campaign.segment.resolution;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One selectable option in a clarification.
 *
 * <p>The option id is opaque and stable: it is derived from the clause and the candidate definition id, so a
 * caller cannot fabricate an id for a definition that was not offered, and re-resolving the same inputs
 * reproduces the same ids. The candidate definition id is carried for host-side application, and the label
 * is presentation data only: logic keys on the option id, never on the label.
 */
public record ClarificationOption(String optionId, String definitionId, String label) {

    private static final Pattern FORMAT = Pattern.compile("^option:[0-9a-f]{64}$");
    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ClarificationOption {
        if (definitionId == null || label == null || label.length() > ResolutionLimits.MAX_OPTION_LABEL_LENGTH) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT, "A clarification option requires bounded fields");
        }
        if (optionId == null || !FORMAT.matcher(optionId).matches()) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT, "A clarification option id is malformed");
        }
    }

    /** Derives the opaque, stable option id from the clause and candidate definition id. */
    public static String deriveOptionId(String clauseId, String definitionId) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("clauseId", clauseId);
        material.put("definitionId", definitionId);
        ContentHash hash = CANONICAL_JSON.hash(material);
        return "option:" + hash.value().substring("sha256:".length());
    }
}