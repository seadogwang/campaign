package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The deterministic replay identifier of one Case attempt.
 *
 * <p>It is derived only from frozen run/case/attempt/configuration pins and safe hashes, so two identical
 * frozen runs produce the same identifier and a changed pin produces a different one. It deliberately does not
 * cover the utterance, raw model output, member identifiers, tenant/user/DataScope values, a credential or any
 * secret: the material is a closed map of the run contract hash, the case pins, the attempt number and the
 * attempt configuration, and nothing else.
 */
public final class ReplayIdentifier {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private ReplayIdentifier() {
    }

    /**
     * Derives the replay identifier of one attempt.
     *
     * @param runContractHash the frozen run contract hash
     * @param casePinsHash the frozen per-Case pin hash
     * @param attemptNumber the stable deterministic attempt number
     * @param attemptConfigurationCode the frozen attempt configuration code
     */
    public static String of(
            ContentHash runContractHash,
            ContentHash casePinsHash,
            int attemptNumber,
            String attemptConfigurationCode) {
        if (runContractHash == null || casePinsHash == null || attemptConfigurationCode == null) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.PIN_MISSING,
                    "A replay identifier requires the frozen run, case and configuration pins");
        }
        if (attemptNumber < 1) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.RUN_IDENTIFIER_INVALID,
                    "A replay identifier requires a positive deterministic attempt number");
        }
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("replayIdVersion", T13Versions.REPLAY_ID_VERSION);
        material.put("runContractHash", runContractHash.value());
        material.put("casePinsHash", casePinsHash.value());
        material.put("attemptNumber", attemptNumber);
        material.put("attemptConfiguration", attemptConfigurationCode);
        ContentHash hash = CANONICAL_JSON.hash(material);
        return "replay-" + hash.value().substring("sha256:".length());
    }
}