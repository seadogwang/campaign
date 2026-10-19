package com.company.campaign.evaluation.segment.validation;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.Issue;
import com.company.campaign.evaluation.segment.contract.CaseInput;
import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.ExpectedIntentSlot;
import com.company.campaign.evaluation.segment.contract.ExpectedResult;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Checks whether a corpus is more than large enough: whether its cases actually mean different things.
 *
 * <p>Counts alone cannot separate a reviewed corpus from a template with numeric suffixes, and Segment
 * specification section 21 says explicitly not to validate semantics with count alone. This validator
 * therefore looks for the shapes a padded corpus takes: two cases that say the same thing, a case whose
 * utterance differs from another's only by a trailing sequence while the expectation is identical, obvious
 * placeholder text standing in for a real user statement, a resolved case with nothing selected, a
 * high-risk case that names no assumption it refuses to make, a refinement case with no target, and a
 * selected or rejected candidate the Metadata snapshot does not even declare.
 *
 * <p>Nothing here reads a clock or a default locale: signatures are built from canonical JSON and compared
 * with {@code Locale.ROOT} case folding only where a machine code is already case-insensitive by contract.
 */
public final class CorpusQualityValidator {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private static final Pattern TRAILING_SEQUENCE = Pattern.compile("[\\s._:-]*\\d+\\s*$");

    private static final List<String> PLACEHOLDER_TOKENS = List.of(
            "todo", "tbd", "fixme", "placeholder", "lorem ipsum", "dummy", "change me", "xxx");

    /** Reports the quality findings for every case, in manifest order. */
    public List<Issue> validate(CorpusBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        List<Issue> issues = new ArrayList<>();
        Map<String, String> signatureOwner = new LinkedHashMap<>();
        Map<String, String> expectationOwner = new LinkedHashMap<>();

        List<CaseInput> cases = bundle.cases();
        List<ExpectedResult> expectedResults = bundle.expectedResults();
        for (int index = 0; index < cases.size(); index++) {
            CaseInput caseInput = cases.get(index);
            ExpectedResult expected = expectedResults.get(index);
            String path = "/cases/" + index + "/" + caseInput.caseId();

            validatePlaceholderText(caseInput, path, issues);
            validateDispositionRequirements(caseInput, expected, path, issues);
            validateRefinementTarget(caseInput, expected, path, issues);
            validateClone(caseInput, expected, signatureOwner, expectationOwner, path, issues);
        }
        return issues;
    }

    private void validatePlaceholderText(CaseInput caseInput, String path, List<Issue> issues) {
        String lowered = caseInput.utterance().toLowerCase(Locale.ROOT);
        for (String token : PLACEHOLDER_TOKENS) {
            if (lowered.contains(token)) {
                issues.add(SegmentEvaluationValidator.issue(
                        EvaluationErrorCodes.PLACEHOLDER_TEXT, path + "/utterance",
                        "Case '" + caseInput.caseId() + "' carries placeholder text rather than a"
                                + " reviewable utterance"));
                return;
            }
        }
    }

    private void validateDispositionRequirements(
            CaseInput caseInput, ExpectedResult expected, String path, List<Issue> issues) {

        Disposition disposition = expected.expectedDisposition();
        if (disposition == Disposition.RESOLVED && expected.expectedSelectedCandidateIds().isEmpty()) {
            issues.add(SegmentEvaluationValidator.issue(
                    EvaluationErrorCodes.RESOLVED_SELECTED_CANDIDATES_MISSING,
                    path + "/expectedSelectedCandidateIds",
                    "Case '" + caseInput.caseId() + "' is RESOLVED but selects no Metadata candidate"));
        }
        if (caseInput.riskLevel() == com.company.campaign.evaluation.segment.contract.RiskLevel.HIGH
                && expected.forbiddenAssumptions().isEmpty()) {
            issues.add(SegmentEvaluationValidator.issue(
                    EvaluationErrorCodes.HIGH_RISK_FORBIDDEN_ASSUMPTIONS_MISSING,
                    path + "/forbiddenAssumptions",
                    "High-risk case '" + caseInput.caseId()
                            + "' declares no forbidden assumption, so a silent wrong reading is unconstrained"));
        }
        if (disposition == Disposition.NEEDS_INPUT
                && (expected.expectedClarificationQuestionCodes().isEmpty()
                        || expected.forbiddenAssumptions().isEmpty())) {
            issues.add(SegmentEvaluationValidator.issue(
                    EvaluationErrorCodes.NEEDS_INPUT_EVIDENCE_MISSING,
                    path + "/expectedClarificationQuestionCodes",
                    "Case '" + caseInput.caseId() + "' expects NEEDS_INPUT but declares no question code"
                            + " and forbidden-assumption evidence"));
        }
        if ((disposition == Disposition.UNSUPPORTED || disposition == Disposition.DENIED)
                && !expected.expectedSelectedCandidateIds().isEmpty()) {
            issues.add(SegmentEvaluationValidator.issue(
                    EvaluationErrorCodes.NON_RESOLVED_SELECTED_CANDIDATES_PRESENT,
                    path + "/expectedSelectedCandidateIds",
                    "Case '" + caseInput.caseId() + "' expects " + disposition
                            + " and must not select a Metadata candidate"));
        }
    }

    private void validateRefinementTarget(
            CaseInput caseInput, ExpectedResult expected, String path, List<Issue> issues) {

        if (caseInput.category() == SegmentCategory.REFINEMENT_ADD_CHANGE_DELETE_MOVE
                && expected.refinementTarget() == null) {
            issues.add(SegmentEvaluationValidator.issue(
                    EvaluationErrorCodes.REFINEMENT_TARGET_MISSING, path + "/refinementTarget",
                    "Refinement case '" + caseInput.caseId() + "' declares no stable refinement target"));
        }
    }

    private void validateClone(
            CaseInput caseInput,
            ExpectedResult expected,
            Map<String, String> signatureOwner,
            Map<String, String> expectationOwner,
            String path,
            List<Issue> issues) {

        String signature = semanticSignature(caseInput, expected);
        String previous = signatureOwner.putIfAbsent(signature, caseInput.caseId());
        if (previous != null) {
            issues.add(SegmentEvaluationValidator.issue(
                    EvaluationErrorCodes.DUPLICATE_CASE_SEMANTICS, path,
                    "Case '" + caseInput.caseId() + "' repeats the utterance, locale and expectation of '"
                            + previous + "' in category " + caseInput.category().name()));
            return;
        }

        // A case whose utterance differs from another's only by a trailing sequence, while its expectation
        // (everything but the utterance) is identical, is a template clone however its id is spelled.
        String expectation = expectationSignature(caseInput, expected);
        String variationKey = caseInput.category().name() + "|"
                + TRAILING_SEQUENCE.matcher(caseInput.utterance()).replaceFirst("").strip();
        String key = variationKey + "|" + expectation;
        String cloneOf = expectationOwner.putIfAbsent(key, caseInput.caseId());
        if (cloneOf != null) {
            issues.add(SegmentEvaluationValidator.issue(
                    EvaluationErrorCodes.TEMPLATE_CLONE_CASE, path,
                    "Case '" + caseInput.caseId() + "' differs from '" + cloneOf
                            + "' only by a trailing sequence while its expectation is identical"));
        }
    }

    private static String semanticSignature(CaseInput caseInput, ExpectedResult expected) {
        Map<String, Object> signature = new TreeMap<>();
        signature.put("category", caseInput.category().name());
        signature.put("utterance", caseInput.utterance());
        signature.put("locales", caseInput.locales().stream().map(Object::toString).sorted().toList());
        signature.put("expectation", expectationFields(caseInput, expected));
        return CANONICAL_JSON.writeString(signature);
    }

    private static String expectationSignature(CaseInput caseInput, ExpectedResult expected) {
        Map<String, Object> signature = new TreeMap<>();
        signature.put("category", caseInput.category().name());
        signature.put("expectation", expectationFields(caseInput, expected));
        return CANONICAL_JSON.writeString(signature);
    }

    private static Map<String, Object> expectationFields(CaseInput caseInput, ExpectedResult expected) {
        Map<String, Object> fields = new TreeMap<>();
        fields.put("risk", caseInput.riskLevel().name());
        fields.put("complex", caseInput.complexSemantics());
        fields.put("disposition", expected.expectedDisposition().name());
        fields.put("slots", slotSignatures(expected.expectedIntentSlots()));
        fields.put("selected", new ArrayList<>(expected.expectedSelectedCandidateIds()));
        fields.put("rejected", new ArrayList<>(expected.expectedRejectedCandidateIds()));
        fields.put("ast", canonical(expected.expectedCanonicalAst()));
        fields.put("validation", new ArrayList<>(expected.expectedValidationIssueCodes()));
        fields.put("questions", new ArrayList<>(expected.expectedClarificationQuestionCodes()));
        fields.put("members", memberSignatures(expected.expectedMemberIdsByFixture()));
        fields.put("claims", new ArrayList<>(expected.groundedExplanationClaimIds()));
        fields.put("forbidden", new ArrayList<>(expected.forbiddenAssumptions()));
        fields.put("refinement", refinementSignature(expected));
        fields.put("fixtures", new ArrayList<>(caseInput.fixtures()));
        return fields;
    }

    private static List<Map<String, Object>> slotSignatures(List<ExpectedIntentSlot> slots) {
        return slots.stream()
                .map(slot -> {
                    Map<String, Object> entry = new TreeMap<>();
                    entry.put("slotId", slot.slotId());
                    entry.put("concept", slot.concept());
                    entry.put("operator", slot.operatorIntent());
                    entry.put("grounded", slot.grounded());
                    entry.put("span", slot.sourceSpan() == null ? null : slot.sourceSpan().text());
                    entry.put("value", canonical(slot.value()));
                    return entry;
                })
                .sorted(Comparator.comparing(entry -> String.valueOf(entry.get("slotId"))))
                .toList();
    }

    private static Map<String, List<String>> memberSignatures(Map<String, MemberIdSet> sets) {
        Map<String, List<String>> signatures = new TreeMap<>();
        sets.forEach((fixtureId, set) -> signatures.put(fixtureId, new ArrayList<>(set.memberIds())));
        return signatures;
    }

    private static String refinementSignature(ExpectedResult expected) {
        if (expected.refinementTarget() == null) {
            return "none";
        }
        return expected.refinementTarget().operation().name() + ":"
                + expected.refinementTarget().targetRef() + ":"
                + String.valueOf(expected.refinementTarget().scopeRef());
    }

    private static String canonical(JsonNode node) {
        return node == null ? "null" : CANONICAL_JSON.writeString(node);
    }
}