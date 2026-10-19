package com.company.campaign.segment.intent;

/**
 * Stable machine codes owned by the structured {@link SegmentIntent} boundary.
 *
 * <p>They follow the Segment family convention: the constant name is uppercase snake case and the wire
 * value carries the {@code SEGMENT_INTENT_} prefix, so a code is unambiguous across families. Codes are
 * never renamed or reused; a new failure mode is given a new code.
 *
 * <p>Messages and details built from these codes never echo the utterance, source-span text, candidate
 * labels, Metadata content or any trusted identifier, because the Intent boundary parses untrusted model
 * output and must stay non-disclosing.
 */
public final class IntentErrorCodes {

    private IntentErrorCodes() {
    }

    /** The declared {@code intentVersion} is not the exact supported version. */
    public static final String VERSION_UNSUPPORTED = "SEGMENT_INTENT_VERSION_UNSUPPORTED";
    /** The document is not well-formed JSON text or bytes. */
    public static final String JSON_MALFORMED = "SEGMENT_INTENT_JSON_MALFORMED";
    /** The JSON document repeats a key. */
    public static final String JSON_DUPLICATE_KEY = "SEGMENT_INTENT_JSON_DUPLICATE_KEY";
    /** An object carries a property that is not part of the Intent contract. */
    public static final String UNKNOWN_FIELD = "SEGMENT_INTENT_UNKNOWN_FIELD";
    /** An object carries a reserved trusted/host-owned property that Intent must never contain. */
    public static final String RESERVED_FIELD = "SEGMENT_INTENT_RESERVED_FIELD";
    /** A discriminator value is not one of the closed variants. */
    public static final String UNKNOWN_DISCRIMINATOR = "SEGMENT_INTENT_UNKNOWN_DISCRIMINATOR";
    /** A value has the wrong JSON shape for its declared kind. */
    public static final String SHAPE_INVALID = "SEGMENT_INTENT_SHAPE_INVALID";
    /** A structural bound (bytes, clauses, depth, width, list size, text size) was exceeded. */
    public static final String LIMIT_EXCEEDED = "SEGMENT_INTENT_LIMIT_EXCEEDED";
    /** The subject is not the exact supported subject. */
    public static final String SUBJECT_INVALID = "SEGMENT_INTENT_SUBJECT_INVALID";
    /** A clause id is absent or does not match the bounded grammar. */
    public static final String CLAUSE_ID_INVALID = "SEGMENT_INTENT_CLAUSE_ID_INVALID";
    /** Two clauses share one id. */
    public static final String DUPLICATE_CLAUSE_ID = "SEGMENT_INTENT_DUPLICATE_CLAUSE_ID";
    /** The clause list is null, empty or not a list. */
    public static final String CLAUSES_INVALID = "SEGMENT_INTENT_CLAUSES_INVALID";
    /** A machine code or concept text is not a bounded uppercase code. */
    public static final String MACHINE_CODE_INVALID = "SEGMENT_INTENT_MACHINE_CODE_INVALID";
    /** A bounded free-text field is null, blank or over the text limit. */
    public static final String TEXT_INVALID = "SEGMENT_INTENT_TEXT_INVALID";
    /** The logical expression is missing or malformed. */
    public static final String EXPRESSION_INVALID = "SEGMENT_INTENT_EXPRESSION_INVALID";
    /** The logical expression references a clause id that does not exist. */
    public static final String EXPRESSION_DANGLING_REFERENCE = "SEGMENT_INTENT_EXPRESSION_DANGLING_REFERENCE";
    /** The logical expression references one clause more than once. */
    public static final String EXPRESSION_DUPLICATE_REFERENCE = "SEGMENT_INTENT_EXPRESSION_DUPLICATE_REFERENCE";
    /** A declared clause is never referenced by the logical expression. */
    public static final String EXPRESSION_UNREFERENCED_CLAUSE = "SEGMENT_INTENT_EXPRESSION_UNREFERENCED_CLAUSE";
    /** A unary NOT does not have exactly one child. */
    public static final String EXPRESSION_NOT_ARITY = "SEGMENT_INTENT_EXPRESSION_NOT_ARITY";
    /** A group has no children or more children than the width bound allows. */
    public static final String EXPRESSION_WIDTH_INVALID = "SEGMENT_INTENT_EXPRESSION_WIDTH_INVALID";
    /** A typed raw value is malformed for its declared kind. */
    public static final String VALUE_INVALID = "SEGMENT_INTENT_VALUE_INVALID";
    /** A window intent is malformed for its declared kind. */
    public static final String WINDOW_INVALID = "SEGMENT_INTENT_WINDOW_INVALID";
    /** A currency intent is malformed. */
    public static final String CURRENCY_INVALID = "SEGMENT_INTENT_CURRENCY_INVALID";
    /** A relation-scope intent is malformed. */
    public static final String RELATION_SCOPE_INVALID = "SEGMENT_INTENT_RELATION_SCOPE_INVALID";
    /** A Metric-scoped dynamic filter is malformed or attached to a non-Metric clause. */
    public static final String SCOPE_FILTER_INVALID = "SEGMENT_INTENT_SCOPE_FILTER_INVALID";
    /** Two Metric-scoped dynamic filters share one filter id. */
    public static final String DUPLICATE_SCOPE_FILTER_ID = "SEGMENT_INTENT_DUPLICATE_SCOPE_FILTER_ID";
    /** A clause carries no provenance, or more than one provenance form. */
    public static final String PROVENANCE_INVALID = "SEGMENT_INTENT_PROVENANCE_INVALID";
    /**
     * An Existing Audience Reference intent is malformed: an absent or unbounded identity, no candidate text,
     * a repeated or unbounded candidate, or a candidate set the contract cannot carry.
     */
    public static final String AUDIENCE_REFERENCE_INVALID = "SEGMENT_INTENT_AUDIENCE_REFERENCE_INVALID";
    /** A source span is absent. */
    public static final String SPAN_MISSING = "SEGMENT_INTENT_SPAN_MISSING";
    /** A source span is empty; a clause span must carry user text. */
    public static final String SPAN_EMPTY = "SEGMENT_INTENT_SPAN_EMPTY";
    /** A source span lies outside the original utterance. */
    public static final String SPAN_OUT_OF_RANGE = "SEGMENT_INTENT_SPAN_OUT_OF_RANGE";
    /** A source span boundary splits a UTF-16 surrogate pair. */
    public static final String SPAN_SURROGATE_SPLIT = "SEGMENT_INTENT_SPAN_SURROGATE_SPLIT";
    /** A source span's text does not equal the exact substring of the original utterance. */
    public static final String SPAN_TEXT_MISMATCH = "SEGMENT_INTENT_SPAN_TEXT_MISMATCH";
    /** A registered-default reference is malformed. */
    public static final String DEFAULT_REFERENCE_INVALID = "SEGMENT_INTENT_DEFAULT_REFERENCE_INVALID";
    /** The injected registry has no such default; model output cannot invent one. */
    public static final String DEFAULT_UNKNOWN = "SEGMENT_INTENT_DEFAULT_UNKNOWN";
    /** The registry's revision does not exactly match the requested version/hash. */
    public static final String DEFAULT_REVISION_MISMATCH = "SEGMENT_INTENT_DEFAULT_REVISION_MISMATCH";
    /** The utterance to which spans are grounded is absent. */
    public static final String UTTERANCE_MISSING = "SEGMENT_INTENT_UTTERANCE_MISSING";
}
