package com.company.campaign.evaluation.segment.contract;

/**
 * Stable machine-readable codes for evaluation-asset loading and validation.
 *
 * <p>Every code here is part of the evaluation wire contract: the loader throws them on
 * {@link EvaluationException} and the validator reports them as {@code Issue} codes. Callers, Gate
 * reports and corpus tooling branch on these values, so an existing code must never be renamed or reused
 * for a different meaning. A new failure mode gets a new code.
 *
 * <p>T03 deliberately does not extend {@code ContractErrorCodes}: the T01 codes describe base envelopes,
 * these describe evaluation assets, and keeping them apart stops a Gate from appearing to fail on a base
 * contract error.
 */
public final class EvaluationErrorCodes {

    // --- Loading: paths and files ---------------------------------------------------------------

    /** A required asset file does not exist or is not a regular file. */
    public static final String ASSET_NOT_FOUND = "EVALUATION_ASSET_NOT_FOUND";

    /** An asset file exists but cannot be read as bytes. */
    public static final String ASSET_UNREADABLE = "EVALUATION_ASSET_UNREADABLE";

    /** A file reference is empty, is not a well-formed path, or is not a normalized relative path. */
    public static final String PATH_MALFORMED = "EVALUATION_PATH_MALFORMED";

    /** A file reference is absolute (including drive-qualified, UNC or root-relative forms). */
    public static final String PATH_ABSOLUTE = "EVALUATION_PATH_ABSOLUTE";

    /** A file reference contains a parent-directory segment. */
    public static final String PATH_TRAVERSAL = "EVALUATION_PATH_TRAVERSAL";

    /** A file reference resolves outside the configured evaluation root. */
    public static final String PATH_OUTSIDE_ROOT = "EVALUATION_PATH_OUTSIDE_ROOT";

    /** An asset file's bytes do not match the SHA-256 hash declared for it. */
    public static final String HASH_MISMATCH = "EVALUATION_HASH_MISMATCH";

    /** A corpus manifest's own content hash does not match its content. */
    public static final String MANIFEST_HASH_MISMATCH = "EVALUATION_MANIFEST_HASH_MISMATCH";

    // --- Loading: structure ---------------------------------------------------------------------

    /** The same case, fixture, metadata snapshot or reference identifier appears more than once. */
    public static final String DUPLICATE_ID = "EVALUATION_DUPLICATE_ID";

    /** Two manifest entries reference the same case or expected file. */
    public static final String DUPLICATE_REFERENCE = "EVALUATION_DUPLICATE_REFERENCE";

    /** A manifest case reference has no paired expected-result reference. */
    public static final String CASE_EXPECTED_MISSING = "EVALUATION_CASE_EXPECTED_MISSING";

    /** A case and its expected result disagree on {@code caseId}, or a reference does not match its file. */
    public static final String CASE_EXPECTED_MISMATCH = "EVALUATION_CASE_EXPECTED_MISMATCH";

    /** A parsed asset is internally inconsistent in a way that must never be representable. */
    public static final String ASSET_INVALID = "EVALUATION_ASSET_INVALID";

    /**
     * An asset's bytes are valid JSON but are not the canonical serialization of the value they contain.
     */
    public static final String ASSET_NOT_CANONICAL = "EVALUATION_ASSET_NOT_CANONICAL";

    /** An identifier is missing or outside its bounded grammar. */
    public static final String ID_INVALID = "EVALUATION_ID_INVALID";

    /** An expected member identifier is missing or outside its bounded grammar. */
    public static final String MEMBER_ID_INVALID = "EVALUATION_MEMBER_ID_INVALID";

    /** An expected member set contains the same member identifier more than once. */
    public static final String MEMBER_ID_DUPLICATE = "EVALUATION_MEMBER_ID_DUPLICATE";

    /** A set-like expectation list contains the same value more than once. */
    public static final String DUPLICATE_SET_VALUE = "EVALUATION_DUPLICATE_SET_VALUE";

    /** A fixture payload declared as an execution context is not a valid trusted execution context. */
    public static final String CONTEXT_PAYLOAD_INVALID = "EVALUATION_CONTEXT_PAYLOAD_INVALID";

    /** A fixture declares time-context values that contradict each other. */
    public static final String FIXTURE_TIME_CONTEXT_INVALID = "EVALUATION_FIXTURE_TIME_CONTEXT_INVALID";

    // --- Validation: case semantics -------------------------------------------------------------

    /** A case declares no locale. */
    public static final String CASE_LOCALES_MISSING = "EVALUATION_CASE_LOCALES_MISSING";

    /** A case utterance is empty or blank. */
    public static final String UTTERANCE_BLANK = "EVALUATION_UTTERANCE_BLANK";

    /** A case references no fixture. */
    public static final String FIXTURES_MISSING = "EVALUATION_FIXTURES_MISSING";

    /** A case references a Metadata snapshot that is not in the Metadata catalog. */
    public static final String METADATA_SNAPSHOT_UNKNOWN = "EVALUATION_METADATA_SNAPSHOT_UNKNOWN";

    /** A case references a fixture that is not in the fixture catalog. */
    public static final String FIXTURE_UNKNOWN = "EVALUATION_FIXTURE_UNKNOWN";

    /** A fixture reference is used in a role it does not declare. */
    public static final String FIXTURE_ROLE_MISMATCH = "EVALUATION_FIXTURE_ROLE_MISMATCH";

    /** A case has no usable trusted execution-context fixture, or it declares the wrong role. */
    public static final String EXECUTION_CONTEXT_FIXTURE_INVALID = "EVALUATION_EXECUTION_CONTEXT_FIXTURE_INVALID";

    /** The trusted execution context payload names a Metadata snapshot other than the case's. */
    public static final String CONTEXT_SNAPSHOT_MISMATCH = "EVALUATION_CONTEXT_SNAPSHOT_MISMATCH";

    /** Fixtures compared by one case do not share one fixed asOf, timezone and watermark. */
    public static final String FIXTURE_TIME_CONTEXT_MISMATCH = "EVALUATION_FIXTURE_TIME_CONTEXT_MISMATCH";

    /** A fixture the case references has no expected member set. */
    public static final String EXPECTED_SET_MISSING = "EVALUATION_EXPECTED_SET_MISSING";

    /** An expected member set exists for a fixture the case does not reference. */
    public static final String EXPECTED_SET_ORPHANED = "EVALUATION_EXPECTED_SET_ORPHANED";

    /** An expected member identifier is not in the referenced fixture's declared universe. */
    public static final String MEMBER_ID_OUTSIDE_UNIVERSE = "EVALUATION_MEMBER_ID_OUTSIDE_UNIVERSE";

    /** A complex-semantics case does not have at least two fixtures including a counterexample. */
    public static final String COMPLEX_FIXTURES_INSUFFICIENT = "EVALUATION_COMPLEX_FIXTURES_INSUFFICIENT";

    /** An expected Intent slot that claims grounding has no source-span evidence. */
    public static final String SOURCE_SPAN_MISSING = "EVALUATION_SOURCE_SPAN_MISSING";

    /** A source span falls outside the utterance's Java string bounds. */
    public static final String SOURCE_SPAN_OUT_OF_BOUNDS = "EVALUATION_SOURCE_SPAN_OUT_OF_BOUNDS";

    /** A source span's text does not equal the utterance range it claims. */
    public static final String SOURCE_SPAN_TEXT_MISMATCH = "EVALUATION_SOURCE_SPAN_TEXT_MISMATCH";

    /** A {@code NEEDS_INPUT} case declares no expected clarification question code. */
    public static final String CLARIFICATION_CODES_MISSING = "EVALUATION_CLARIFICATION_CODES_MISSING";

    /** A non-resolved disposition carries an expected canonical AST that it must not. */
    public static final String EXPECTED_AST_NOT_PERMITTED = "EVALUATION_EXPECTED_AST_NOT_PERMITTED";

    // --- Validation: review evidence ------------------------------------------------------------

    /** A case declares no review-evidence file at all, so its review ledger is missing. */
    public static final String REVIEW_EVIDENCE_MISSING = "EVALUATION_REVIEW_EVIDENCE_MISSING";

    /** A review ledger's own content hash does not match its content, or it declares none. */
    public static final String REVIEW_HASH_MISMATCH = "EVALUATION_REVIEW_HASH_MISMATCH";

    /** A review ledger's caseId does not match the case it is attached to. */
    public static final String REVIEW_CASE_MISMATCH = "EVALUATION_REVIEW_CASE_MISMATCH";

    /**
     * A review ledger binds a case or expected-result hash other than the reviewed revision's, so the
     * decision does not cover the bytes it now points at.
     */
    public static final String REVIEW_BINDING_STALE = "EVALUATION_REVIEW_BINDING_STALE";

    /** A review ledger declares the same role more than once. */
    public static final String REVIEW_ROLE_DUPLICATE = "EVALUATION_REVIEW_ROLE_DUPLICATE";

    /** A review ledger is missing an entry for one of the three required roles. */
    public static final String REVIEW_ROLE_MISSING = "EVALUATION_REVIEW_ROLE_MISSING";

    /** A review entry is structurally invalid in a way the contract cannot represent. */
    public static final String REVIEW_ENTRY_INVALID = "EVALUATION_REVIEW_ENTRY_INVALID";

    /** A reviewer or evidence reference is missing or outside its bounded non-sensitive grammar. */
    public static final String REVIEW_REFERENCE_INVALID = "EVALUATION_REVIEW_REFERENCE_INVALID";

    /** Review notes are blank, oversized, or shaped like a structured payload. */
    public static final String REVIEW_NOTES_INVALID = "EVALUATION_REVIEW_NOTES_INVALID";

    /** A pending review entry fabricates a reviewer, evidence reference or decision time. */
    public static final String REVIEW_DECISION_FABRICATED = "EVALUATION_REVIEW_DECISION_FABRICATED";

    /** A required role's review is still pending, so the case is not independently approved. */
    public static final String REVIEW_PENDING = "EVALUATION_REVIEW_PENDING";

    /** A required role rejected the case. */
    public static final String REVIEW_REJECTED = "EVALUATION_REVIEW_REJECTED";

    // --- Review workflow boundary (T06R) ---------------------------------------------------------

    /** A review snapshot query found no exact case/expected/review revision for the requested key. */
    public static final String REVIEW_SNAPSHOT_NOT_FOUND = "EVALUATION_REVIEW_SNAPSHOT_NOT_FOUND";

    /**
     * A review decision command, actor, snapshot or commit is null, malformed or internally inconsistent.
     */
    public static final String REVIEW_DECISION_INVALID = "EVALUATION_REVIEW_DECISION_INVALID";

    /** A review decision did not state a real APPROVED or REJECTED decision. */
    public static final String REVIEW_STATUS_INVALID = "EVALUATION_REVIEW_STATUS_INVALID";

    /** Trusted review authorization did not explicitly approve the exact actor, role and case key. */
    public static final String REVIEW_AUTHORIZATION_DENIED = "EVALUATION_REVIEW_AUTHORIZATION_DENIED";

    /** A reviewer reference was already used to decide a different role on the same case. */
    public static final String REVIEW_REVIEWER_REUSED = "EVALUATION_REVIEW_REVIEWER_REUSED";

    /** A review decision lost the compare-and-set race against the current review hash. */
    public static final String REVIEW_CAS_CONFLICT = "EVALUATION_REVIEW_CAS_CONFLICT";

    /** A review repository commit failed for a reason other than a lost compare-and-set race. */
    public static final String REVIEW_COMMIT_FAILED = "EVALUATION_REVIEW_COMMIT_FAILED";

    /** A review audit event's declared hash does not match its content. */
    public static final String REVIEW_AUDIT_HASH_MISMATCH = "EVALUATION_REVIEW_AUDIT_HASH_MISMATCH";

    /** A development-only corpus review result was required to be Gate-eligible. */
    public static final String REVIEW_NOT_GATE_ELIGIBLE = "EVALUATION_REVIEW_NOT_GATE_ELIGIBLE";

    // --- Validation: corpus quality -------------------------------------------------------------

    /** Two cases in one category share the same utterance, locale set and semantic signature. */
    public static final String DUPLICATE_CASE_SEMANTICS = "EVALUATION_DUPLICATE_CASE_SEMANTICS";

    /** A case looks like a template clone: only a trailing sequence differs and the expectation is equal. */
    public static final String TEMPLATE_CLONE_CASE = "EVALUATION_TEMPLATE_CLONE_CASE";

    /** An utterance, tag or note carries obvious placeholder text rather than a real corpus statement. */
    public static final String PLACEHOLDER_TEXT = "EVALUATION_PLACEHOLDER_TEXT";

    /** A resolved case declares no selected Metadata candidate. */
    public static final String RESOLVED_SELECTED_CANDIDATES_MISSING =
            "EVALUATION_RESOLVED_SELECTED_CANDIDATES_MISSING";

    /** A high-risk case declares no forbidden assumption. */
    public static final String HIGH_RISK_FORBIDDEN_ASSUMPTIONS_MISSING =
            "EVALUATION_HIGH_RISK_FORBIDDEN_ASSUMPTIONS_MISSING";

    /** A needs-input case declares no clarification question code or no forbidden assumption. */
    public static final String NEEDS_INPUT_EVIDENCE_MISSING = "EVALUATION_NEEDS_INPUT_EVIDENCE_MISSING";

    /** An unsupported or denied case selects Metadata candidates that it must not. */
    public static final String NON_RESOLVED_SELECTED_CANDIDATES_PRESENT =
            "EVALUATION_NON_RESOLVED_SELECTED_CANDIDATES_PRESENT";

    /** A refinement-category case declares no refinement target. */
    public static final String REFINEMENT_TARGET_MISSING = "EVALUATION_REFINEMENT_TARGET_MISSING";

    /** A selected Metadata candidate id is not declared by the referenced Metadata snapshot. */
    public static final String SELECTED_CANDIDATE_UNKNOWN = "EVALUATION_SELECTED_CANDIDATE_UNKNOWN";

    /** A rejected Metadata candidate id is not declared by the referenced Metadata snapshot. */
    public static final String REJECTED_CANDIDATE_UNKNOWN = "EVALUATION_REJECTED_CANDIDATE_UNKNOWN";

    // --- Validation: T05 DSL expectation integration ---------------------------------------------

    /** An expected canonical AST is not a structurally valid T05 Segment DSL 1.3 document. */
    public static final String EXPECTED_AST_NOT_T05_VALID = "EVALUATION_EXPECTED_AST_NOT_T05_VALID";

    /** An expected canonical AST's Metadata binding does not match the case's Metadata descriptor. */
    public static final String EXPECTED_AST_METADATA_BINDING_MISMATCH =
            "EVALUATION_EXPECTED_AST_METADATA_BINDING_MISMATCH";

    /** An expected canonical AST references an attribute, metric or relation the snapshot does not declare. */
    public static final String EXPECTED_AST_REFERENCE_UNKNOWN = "EVALUATION_EXPECTED_AST_REFERENCE_UNKNOWN";

    // --- Validation: Metadata payload compatibility ----------------------------------------------

    /** A Metadata snapshot payload is not a valid T04 Metadata snapshot. */
    public static final String METADATA_PAYLOAD_INVALID = "EVALUATION_METADATA_PAYLOAD_INVALID";

    /** A Metadata snapshot payload's identity does not match the catalog descriptor that points at it. */
    public static final String METADATA_PAYLOAD_IDENTITY_MISMATCH =
            "EVALUATION_METADATA_PAYLOAD_IDENTITY_MISMATCH";

    // --- Validation: Gate readiness -------------------------------------------------------------

    /** A Golden Corpus category has no case at all. */
    public static final String CATEGORY_MISSING = "EVALUATION_CATEGORY_MISSING";

    /** A Golden Corpus category has fewer cases than the manifest requires. */
    public static final String CATEGORY_UNDER_MINIMUM = "EVALUATION_CATEGORY_UNDER_MINIMUM";

    /** A Gate-ready manifest requires fewer than 15 cases per category. */
    public static final String GATE_MINIMUM_TOO_LOW = "EVALUATION_GATE_MINIMUM_TOO_LOW";

    /** A Gate-ready manifest requires fewer than five high-risk repeats. */
    public static final String GATE_REPEATS_TOO_LOW = "EVALUATION_GATE_REPEATS_TOO_LOW";

    private EvaluationErrorCodes() {
    }
}