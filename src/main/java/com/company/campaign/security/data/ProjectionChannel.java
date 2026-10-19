package com.company.campaign.security.data;

/**
 * The destinations a payload may be projected to.
 *
 * <p>Each channel is an explicit, purpose-built projection rather than a general serializer. The model
 * prompt defaults to excluding {@code RESTRICTED}; the application log and audit metadata are additionally
 * subject to allowlist and PII redaction because they are retained and searchable.
 */
public enum ProjectionChannel {

    MODEL_PROMPT,
    APPLICATION_LOG,
    AUDIT_METADATA
}