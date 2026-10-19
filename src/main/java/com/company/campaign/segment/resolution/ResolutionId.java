package com.company.campaign.segment.resolution;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic identifier of one clarification resolution.
 *
 * <p>It is derived from the canonical immutable inputs, never from a UUID, a random value, a wall clock, a
 * default locale or mutable global state, so re-resolving the same exact inputs reproduces the same id. The
 * form is {@code resolution:<64 lowercase hex characters>}.
 */
public record ResolutionId(String value) {

    private static final Pattern FORMAT = Pattern.compile("^resolution:[0-9a-f]{64}$");
    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ResolutionId {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.RESOLUTION_ID_INVALID, "A resolution id is malformed");
        }
    }

    /** Derives the resolution id from the exact base intent, pins, question, clause and option set. */
    public static ResolutionId derive(
            ContentHash baseIntentHash,
            ResolutionPins pins,
            QuestionCode questionCode,
            String clauseId,
            java.util.List<String> optionIds) {
        if (baseIntentHash == null || pins == null || questionCode == null || clauseId == null) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INPUT_MISSING, "A resolution id requires every base input");
        }
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("baseIntentHash", baseIntentHash.value());
        material.put("snapshotId", pins.snapshotId());
        material.put("snapshotVersion", pins.snapshotVersion());
        material.put("snapshotHash", pins.snapshotHash().value());
        material.put("policyVersion", pins.policyVersion());
        material.put("policyHash", pins.policyHash().value());
        material.put("registryVersion", pins.registryVersion());
        material.put("registryHash", pins.registryHash().value());
        material.put("questionCode", questionCode.name());
        material.put("clauseId", clauseId);
        material.put("optionIds", optionIds == null ? java.util.List.of() : java.util.List.copyOf(optionIds));
        ContentHash hash = CANONICAL_JSON.hash(material);
        return new ResolutionId("resolution:" + hash.value().substring("sha256:".length()));
    }

    @Override
    public String toString() {
        return value;
    }
}