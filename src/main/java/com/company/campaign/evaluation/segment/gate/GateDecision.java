package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.evaluation.segment.contract.GateOutcome;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The canonical proposed Gate decision of one evaluation.
 *
 * <p>It is a <em>proposal</em>, never an approval. It binds the exact T13 report hash, the complete frozen run
 * and version pins copied from that report, the complete deterministic-layer evidence, every threshold check,
 * the bounded issue codes that explain an {@code INVALID} or {@code FAILED} verdict, the frozen review issue
 * codes preserved in bounded evidence, and the explicit host-supplied decision timestamp. It carries no owner,
 * no approval and no capability state: only the host approval service may turn it into an approval.
 *
 * <p>{@link #decisionHash()} covers the decision with the hash field excluded, and {@link #of} is the only way
 * to build a decision carrying a correct hash. {@link #recomputeHash()} re-derives it, so a forged or stale
 * decision is detectable before any approval.
 */
public record GateDecision(
        String decisionSchemaVersion,
        String decisionId,
        GateOutcome outcome,
        ContentHash reportHash,
        GateRunPins runPins,
        DeterministicLayerEvidence deterministicEvidence,
        List<ThresholdCheck> thresholdChecks,
        List<String> issueCodes,
        List<String> reviewIssueCodes,
        Instant decidedAt,
        ContentHash decisionHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public GateDecision {
        if (!GateVersions.DECISION_SCHEMA_VERSION.equals(decisionSchemaVersion)) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_INVALID,
                    "A Gate decision must declare the exact T14 decision contract version");
        }
        if (decisionId == null || !REFERENCE_ID.matcher(decisionId).matches()) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_INVALID, "A Gate decision requires a bounded decision id");
        }
        if (outcome == null || reportHash == null || runPins == null || deterministicEvidence == null
                || decidedAt == null) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_INVALID, "A Gate decision requires its verdict and frozen evidence");
        }
        List<ThresholdCheck> declaredChecks = thresholdChecks == null ? List.of() : thresholdChecks;
        List<ThresholdCheck> validatedChecks = new ArrayList<>(declaredChecks.size());
        for (ThresholdCheck check : declaredChecks) {
            if (check == null) {
                throw GateException.of(
                        GateErrorCodes.APPROVAL_INVALID, "A Gate decision contains a null threshold check");
            }
            validatedChecks.add(check);
        }
        thresholdChecks = List.copyOf(validatedChecks);
        validateThresholdChecks(outcome, thresholdChecks);
        issueCodes = boundedCodes(issueCodes, "issue");
        reviewIssueCodes = boundedCodes(reviewIssueCodes, "review issue");
    }

    /** Builds a decision and stamps it with its own canonical hash. */
    public static GateDecision of(
            String decisionId,
            GateOutcome outcome,
            ContentHash reportHash,
            GateRunPins runPins,
            DeterministicLayerEvidence deterministicEvidence,
            List<ThresholdCheck> thresholdChecks,
            List<String> issueCodes,
            List<String> reviewIssueCodes,
            Instant decidedAt) {
        GateDecision withoutHash = new GateDecision(
                GateVersions.DECISION_SCHEMA_VERSION, decisionId, outcome, reportHash, runPins,
                deterministicEvidence, thresholdChecks, issueCodes, reviewIssueCodes, decidedAt, null);
        return withoutHash.withDecisionHash(CANONICAL_JSON.hash(withoutHash.material()));
    }

    /** The same decision carrying the given hash. */
    public GateDecision withDecisionHash(ContentHash hash) {
        return new GateDecision(
                decisionSchemaVersion, decisionId, outcome, reportHash, runPins, deterministicEvidence,
                thresholdChecks, issueCodes, reviewIssueCodes, decidedAt, hash);
    }

    /** The canonical bytes the decision hash covers: the decision without its own hash component. */
    public byte[] canonicalBytes() {
        return CANONICAL_JSON.write(material());
    }

    /** The canonical decision hash with the hash field excluded. */
    public ContentHash recomputeHash() {
        return CANONICAL_JSON.hash(material());
    }

    /** True when this decision carries every threshold check exactly once, in rule order. */
    public boolean thresholdChecksComplete() {
        if (outcome == GateOutcome.INVALID) {
            return thresholdChecks.isEmpty();
        }
        return matchesRules(thresholdChecks);
    }

    private Object material() {
        return new Material(
                decisionSchemaVersion, decisionId, outcome, reportHash, runPins, deterministicEvidence,
                thresholdChecks, issueCodes, reviewIssueCodes, decidedAt);
    }

    private static void validateThresholdChecks(GateOutcome outcome, List<ThresholdCheck> checks) {
        if (outcome == GateOutcome.INVALID) {
            if (!checks.isEmpty()) {
                throw GateException.of(
                        GateErrorCodes.APPROVAL_INVALID,
                        "An INVALID decision is not threshold-evaluated and must carry no threshold checks");
            }
            return;
        }
        if (!matchesRules(checks)) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_INVALID,
                    "A threshold-evaluated decision must carry every required threshold check exactly once");
        }
    }

    private static boolean matchesRules(List<ThresholdCheck> checks) {
        List<ThresholdRule> rules = GateThresholds.required();
        if (checks.size() != rules.size()) {
            return false;
        }
        for (int index = 0; index < rules.size(); index++) {
            ThresholdRule rule = rules.get(index);
            ThresholdCheck check = checks.get(index);
            if (!rule.metricId().equals(check.metricId()) || rule.scope() != check.scope()) {
                return false;
            }
        }
        return true;
    }

    private static List<String> boundedCodes(List<String> codes, String kind) {
        List<String> source = codes == null ? List.of() : codes;
        Set<String> distinct = new LinkedHashSet<>();
        for (String code : source) {
            if (code == null || !code.matches(ContractPatterns.MACHINE_CODE)) {
                throw GateException.of(
                        GateErrorCodes.APPROVAL_INVALID, "A Gate decision " + kind + " code is not bounded");
            }
            distinct.add(code);
        }
        return List.copyOf(distinct);
    }

    /** The self-excluded hash material: every component except the decision hash itself. */
    private record Material(
            String decisionSchemaVersion,
            String decisionId,
            GateOutcome outcome,
            ContentHash reportHash,
            GateRunPins runPins,
            DeterministicLayerEvidence deterministicEvidence,
            List<ThresholdCheck> thresholdChecks,
            List<String> issueCodes,
            List<String> reviewIssueCodes,
            Instant decidedAt
    ) {
    }
}