package com.company.campaign.evaluation.segment.reference;

/**
 * Stable machine-readable codes owned by the Existing Audience Reference corpus.
 *
 * <p>They are part of the outward contract of the reference corpus validator and evaluator: a Gate report,
 * corpus tooling or a later durable integration branches on these values, so an existing code is never
 * renamed or reused for another meaning. A new failure mode gets a new code.
 *
 * <p>Loading-level failures — a path, a hash, a duplicate identity — reuse
 * {@link com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes}, because they are the same
 * kind of failure the base corpus loader already reports and a second spelling would make one problem look
 * like two. The codes here describe what only a reference corpus can get wrong.
 */
public final class ReferenceCorpusErrorCodes {

    // --- Manifest and corpus shape -----------------------------------------------------------------

    /** A case has no paired expected-result reference. */
    public static final String CASE_EXPECTED_MISSING = "REFERENCE_CORPUS_CASE_EXPECTED_MISSING";

    /** The same case, or the same case/expected path, is declared more than once. */
    public static final String DUPLICATE_CASE = "REFERENCE_CORPUS_DUPLICATE_CASE";

    /** A corpus declares fewer cases than its own manifest minimum asks for. */
    public static final String CASE_COUNT_BELOW_MINIMUM = "REFERENCE_CORPUS_CASE_COUNT_BELOW_MINIMUM";

    /** A corpus that asks to be Gate-ready declares fewer cases than the T18 floor of forty. */
    public static final String GATE_MINIMUM_TOO_LOW = "REFERENCE_CORPUS_GATE_MINIMUM_TOO_LOW";

    /** A required coverage category has no case at all. */
    public static final String CATEGORY_MISSING = "REFERENCE_CORPUS_CATEGORY_MISSING";

    /** A coverage category has fewer cases than the corpus requires. */
    public static final String CATEGORY_UNDER_MINIMUM = "REFERENCE_CORPUS_CATEGORY_UNDER_MINIMUM";

    /** A case declares a category the manifest does not require, so its coverage claim is unchecked. */
    public static final String CATEGORY_UNEXPECTED = "REFERENCE_CORPUS_CATEGORY_UNEXPECTED";

    /** Two cases in one category declare the same scenario, request and expectation. */
    public static final String DUPLICATE_CASE_SEMANTICS = "REFERENCE_CORPUS_DUPLICATE_CASE_SEMANTICS";

    // --- Case shape -------------------------------------------------------------------------------

    /** A case declares a scenario but not the request or fixtures that scenario requires. */
    public static final String CASE_UNDER_SPECIFIED = "REFERENCE_CORPUS_CASE_UNDER_SPECIFIED";

    /** A case declares a request part that its scenario must not carry. */
    public static final String CASE_OVER_SPECIFIED = "REFERENCE_CORPUS_CASE_OVER_SPECIFIED";

    /** A case names a fixture the fixture catalog does not declare. */
    public static final String FIXTURE_UNKNOWN = "REFERENCE_CORPUS_FIXTURE_UNKNOWN";

    /** A fixture is referenced in a role it does not declare. */
    public static final String FIXTURE_ROLE_MISMATCH = "REFERENCE_CORPUS_FIXTURE_ROLE_MISMATCH";

    /** A case declares a pin whose audience/version/hash is missing or outside its bounded grammar. */
    public static final String PIN_INVALID = "REFERENCE_CORPUS_PIN_INVALID";

    /** A case declares two reference sites sharing one stable node identity. */
    public static final String SITE_ID_DUPLICATE = "REFERENCE_CORPUS_SITE_ID_DUPLICATE";

    /** A case utterance, tag or note carries placeholder text rather than a reviewable statement. */
    public static final String PLACEHOLDER_TEXT = "REFERENCE_CORPUS_PLACEHOLDER_TEXT";

    // --- Expectation shape ------------------------------------------------------------------------

    /** An expectation omits an artifact its declared disposition requires. */
    public static final String EXPECTATION_INCOMPLETE = "REFERENCE_CORPUS_EXPECTATION_INCOMPLETE";

    /** An expectation carries an artifact its declared disposition forbids. */
    public static final String EXPECTATION_OVER_SPECIFIED = "REFERENCE_CORPUS_EXPECTATION_OVER_SPECIFIED";

    /** An expectation declares an issue code the reference core does not own. */
    public static final String EXPECTED_ISSUE_CODE_UNKNOWN = "REFERENCE_CORPUS_EXPECTED_ISSUE_CODE_UNKNOWN";

    /** An expectation repeats an issue code, which the ordered, de-duplicated issue list must not. */
    public static final String EXPECTED_ISSUE_CODE_DUPLICATE = "REFERENCE_CORPUS_EXPECTED_ISSUE_CODE_DUPLICATE";

    /** An expectation declares a Resolution question code the reference resolver does not own. */
    public static final String EXPECTED_QUESTION_CODE_UNKNOWN = "REFERENCE_CORPUS_EXPECTED_QUESTION_CODE_UNKNOWN";

    // --- Review and evidence status ---------------------------------------------------------------

    /** A case declares no review-evidence ledger, so no human review can be recorded for it. */
    public static final String REVIEW_EVIDENCE_MISSING = "REFERENCE_CORPUS_REVIEW_EVIDENCE_MISSING";

    /** A review ledger's bound case or expected hash is not this revision's, so the review is stale. */
    public static final String REVIEW_BINDING_STALE = "REFERENCE_CORPUS_REVIEW_BINDING_STALE";

    /** A review ledger records a decision while the manifest still declares review pending. */
    public static final String REVIEW_DECISION_FABRICATED = "REFERENCE_CORPUS_REVIEW_DECISION_FABRICATED";

    /** The manifest declares human review complete but at least one required role is still pending. */
    public static final String REVIEW_INCOMPLETE = "REFERENCE_CORPUS_REVIEW_INCOMPLETE";

    /** The manifest declares a release status the reference corpus must never claim. */
    public static final String RELEASE_STATUS_FABRICATED = "REFERENCE_CORPUS_RELEASE_STATUS_FABRICATED";

    /** The manifest declares model evidence that was never produced by a real model run. */
    public static final String MODEL_EVIDENCE_FABRICATED = "REFERENCE_CORPUS_MODEL_EVIDENCE_FABRICATED";

    // --- Evaluation --------------------------------------------------------------------------------

    /** An evaluated case produced an observation its expectation does not describe. */
    public static final String OBSERVATION_MISMATCH = "REFERENCE_CORPUS_OBSERVATION_MISMATCH";

    /** A case could not be evaluated because a required trusted fixture was absent or malformed. */
    public static final String EVALUATION_INPUT_INVALID = "REFERENCE_CORPUS_EVALUATION_INPUT_INVALID";

    private ReferenceCorpusErrorCodes() {
    }
}