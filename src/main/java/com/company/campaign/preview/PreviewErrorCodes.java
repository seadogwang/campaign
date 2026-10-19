package com.company.campaign.preview;

import java.util.Map;

/**
 * Stable machine-readable codes owned by the T09C read-only Segment Preview boundary.
 *
 * <p>These values are part of the wire contract: the HTTP error envelope and later host adapters branch on
 * them, so an existing code is never renamed or reused for a different meaning and a new failure mode gets a
 * new code. Every message is fixed prose and the only detail a failure carries is a closed {@code invariant}
 * name, so no identifier, operand, SQL fragment, bind parameter, member identifier, fixture fact, exception
 * message or thrown cause is ever echoed outward.
 */
public final class PreviewErrorCodes {

    // ---------------------------------------------------------------- request boundary

    /** The request body was absent or empty. */
    public static final String REQUEST_MISSING = "SEGMENT_PREVIEW_REQUEST_MISSING";

    /** The request body exceeds the configured byte limit. */
    public static final String REQUEST_LIMIT_EXCEEDED = "SEGMENT_PREVIEW_REQUEST_LIMIT_EXCEEDED";

    /** The request body is not well-formed UTF-8. */
    public static final String REQUEST_ENCODING_INVALID = "SEGMENT_PREVIEW_REQUEST_ENCODING_INVALID";

    /** The request body is not a single well-formed JSON document. */
    public static final String REQUEST_JSON_MALFORMED = "SEGMENT_PREVIEW_REQUEST_JSON_MALFORMED";

    /** The request body repeats an object key at some nesting level. */
    public static final String REQUEST_DUPLICATE_KEY = "SEGMENT_PREVIEW_REQUEST_DUPLICATE_KEY";

    /** The request body declares a field outside the closed request shape. */
    public static final String REQUEST_UNKNOWN_FIELD = "SEGMENT_PREVIEW_REQUEST_UNKNOWN_FIELD";

    /** The request body omits a required field. */
    public static final String REQUEST_MISSING_FIELD = "SEGMENT_PREVIEW_REQUEST_MISSING_FIELD";

    /** The request body declares a required field as JSON null. */
    public static final String REQUEST_NULL_FIELD = "SEGMENT_PREVIEW_REQUEST_NULL_FIELD";

    /** A request value has the wrong JSON shape (for example a boolean written as a string). */
    public static final String REQUEST_SHAPE_INVALID = "SEGMENT_PREVIEW_REQUEST_SHAPE_INVALID";

    /** The embedded Segment DSL document was rejected by the accepted strict T05 codec. */
    public static final String REQUEST_DSL_INVALID = "SEGMENT_PREVIEW_REQUEST_DSL_INVALID";

    // ---------------------------------------------------------------- trusted context and Metadata

    /** The host-owned trusted context could not be resolved for this call. */
    public static final String TRUSTED_CONTEXT_UNAVAILABLE = "SEGMENT_PREVIEW_TRUSTED_CONTEXT_UNAVAILABLE";

    /** The Metadata Snapshot registry could not answer for this call. */
    public static final String METADATA_UNAVAILABLE = "SEGMENT_PREVIEW_METADATA_UNAVAILABLE";

    /** The exact Metadata Snapshot pinned by the trusted context is not retained. */
    public static final String METADATA_SNAPSHOT_MISSING = "SEGMENT_PREVIEW_METADATA_SNAPSHOT_MISSING";

    /** The retained Metadata Snapshot is owned by another tenant or program. */
    public static final String METADATA_OWNER_MISMATCH = "SEGMENT_PREVIEW_METADATA_OWNER_MISMATCH";

    /** The client DSL Metadata pins do not agree with the snapshot the trusted context pins. */
    public static final String METADATA_BINDING_MISMATCH = "SEGMENT_PREVIEW_METADATA_BINDING_MISMATCH";

    // ---------------------------------------------------------------- deterministic pipeline

    /** The genuine T07 validator rejected the document; no partial AST or plan exists. */
    public static final String VALIDATION_REJECTED = "SEGMENT_PREVIEW_VALIDATION_REJECTED";

    /** The validation boundary failed unexpectedly. */
    public static final String VALIDATION_FAILED = "SEGMENT_PREVIEW_VALIDATION_FAILED";

    /** The genuine T08 planner refused the validated document for this trusted context. */
    public static final String PLANNING_REJECTED = "SEGMENT_PREVIEW_PLANNING_REJECTED";

    /** The planning boundary failed unexpectedly. */
    public static final String PLANNING_FAILED = "SEGMENT_PREVIEW_PLANNING_FAILED";

    /** The read-only execution backend is not available. */
    public static final String BACKEND_UNAVAILABLE = "SEGMENT_PREVIEW_BACKEND_UNAVAILABLE";

    /** The read-only execution backend did not answer within its bound. */
    public static final String BACKEND_TIMEOUT = "SEGMENT_PREVIEW_BACKEND_TIMEOUT";

    /** The read-only execution backend failed unexpectedly. */
    public static final String BACKEND_FAILED = "SEGMENT_PREVIEW_BACKEND_FAILED";

    /** The backend result contradicts the genuine plan or the trusted context and is refused. */
    public static final String EXECUTION_RESULT_INCOHERENT = "SEGMENT_PREVIEW_EXECUTION_RESULT_INCOHERENT";

    /** The trusted data watermark is older than the configured freshness bound. */
    public static final String DATA_STALE = "SEGMENT_PREVIEW_DATA_STALE";

    /** The exact matched count exceeds the configured result bound. */
    public static final String RESULT_LIMIT_EXCEEDED = "SEGMENT_PREVIEW_RESULT_LIMIT_EXCEEDED";

    // ---------------------------------------------------------------- HTTP adapter

    /** The request media type is not the one supported by the Preview endpoint. */
    public static final String UNSUPPORTED_MEDIA_TYPE = "SEGMENT_PREVIEW_UNSUPPORTED_MEDIA_TYPE";

    /** The request method is not the one supported by the Preview endpoint. */
    public static final String METHOD_NOT_ALLOWED = "SEGMENT_PREVIEW_METHOD_NOT_ALLOWED";

    /** The request body could not be read by the transport. */
    public static final String REQUEST_UNREADABLE = "SEGMENT_PREVIEW_REQUEST_UNREADABLE";

    /** An unexpected internal failure was contained at the Preview boundary. */
    public static final String INTERNAL_FAILURE = "SEGMENT_PREVIEW_INTERNAL_FAILURE";

    /** A mandatory Preview dependency was missing or malformed at construction time. */
    public static final String DEPENDENCY_MISSING = "SEGMENT_PREVIEW_DEPENDENCY_MISSING";

    private static final String FALLBACK = "The Segment Preview request could not be completed.";

    private static final Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry(REQUEST_MISSING, "A Segment Preview request body is required."),
            Map.entry(REQUEST_LIMIT_EXCEEDED, "The Segment Preview request body exceeds the supported size."),
            Map.entry(REQUEST_ENCODING_INVALID, "The Segment Preview request body is not valid UTF-8."),
            Map.entry(REQUEST_JSON_MALFORMED, "The Segment Preview request body is not valid JSON."),
            Map.entry(REQUEST_DUPLICATE_KEY, "The Segment Preview request body repeats a field."),
            Map.entry(REQUEST_UNKNOWN_FIELD, "The Segment Preview request body contains an unsupported field."),
            Map.entry(REQUEST_MISSING_FIELD, "The Segment Preview request body omits a required field."),
            Map.entry(REQUEST_NULL_FIELD, "A required Segment Preview request field is null."),
            Map.entry(REQUEST_SHAPE_INVALID, "A Segment Preview request field has an unsupported shape."),
            Map.entry(REQUEST_DSL_INVALID, "The Segment DSL in the request body is not a valid document."),
            Map.entry(TRUSTED_CONTEXT_UNAVAILABLE, "No trusted execution context is available for this request."),
            Map.entry(METADATA_UNAVAILABLE, "The Metadata Snapshot registry is not available."),
            Map.entry(METADATA_SNAPSHOT_MISSING, "The requested Metadata Snapshot is not available."),
            Map.entry(METADATA_OWNER_MISMATCH, "The requested Metadata Snapshot is not available."),
            Map.entry(METADATA_BINDING_MISMATCH, "The request does not bind the expected Metadata Snapshot."),
            Map.entry(VALIDATION_REJECTED, "The Segment DSL document did not validate."),
            Map.entry(VALIDATION_FAILED, "The Segment DSL document could not be validated."),
            Map.entry(PLANNING_REJECTED, "The Segment DSL document could not be planned."),
            Map.entry(PLANNING_FAILED, "The Segment DSL document could not be planned."),
            Map.entry(BACKEND_UNAVAILABLE, "The execution backend is unavailable."),
            Map.entry(BACKEND_TIMEOUT, "The execution backend did not respond in time."),
            Map.entry(BACKEND_FAILED, "The execution backend failed."),
            Map.entry(EXECUTION_RESULT_INCOHERENT, "The execution result could not be accepted."),
            Map.entry(DATA_STALE, "The available data is older than the supported freshness bound."),
            Map.entry(RESULT_LIMIT_EXCEEDED, "The exact matched count exceeds the supported result bound."),
            Map.entry(UNSUPPORTED_MEDIA_TYPE, "The request media type is not supported."),
            Map.entry(METHOD_NOT_ALLOWED, "The request method is not supported."),
            Map.entry(REQUEST_UNREADABLE, "The request body could not be read."),
            Map.entry(INTERNAL_FAILURE, "The Segment Preview request could not be completed."),
            Map.entry(DEPENDENCY_MISSING, "A required Segment Preview component is missing."));

    private PreviewErrorCodes() {
    }

    /** The fixed, bounded message for a code; never derived from a payload or a thrown cause. */
    public static String message(String code) {
        return MESSAGES.getOrDefault(code, FALLBACK);
    }
}