package com.company.campaign.builder.campaignagent;

import java.util.Objects;

/**
 * An immutable, host-validated confirmed business objective of one Campaign conversation.
 *
 * <p>The objective is a bounded quote from the user's current utterance, validated by deterministic
 * substring match rather than semantic similarity or a model boolean. A confirmed objective survives
 * across turns until an explicit later correction with current-turn evidence replaces it.
 *
 * <p>The value deliberately does not expose its raw text in {@link #toString()}, logs, exception
 * messages, metric labels, prompt hashes or audit identifiers. Diagnostics report only safe status
 * and length metadata.
 */
public record CampaignBusinessObjective(
        String evidence,
        long acceptedAtRevision,
        String evidenceTurnId
) {

    /** The absolute bound on the confirmed objective evidence text, in UTF-16 code units. */
    public static final int MAX_OBJECTIVE_LENGTH = 1024;

    /** The absolute minimum length for meaningful objective evidence. */
    public static final int MIN_OBJECTIVE_LENGTH = 1;

    public CampaignBusinessObjective {
        if (evidence == null || evidence.isBlank()) {
            throw new IllegalArgumentException("A confirmed business objective requires non-blank evidence.");
        }
        if (evidence.length() > MAX_OBJECTIVE_LENGTH) {
            throw new IllegalArgumentException(
                    "A confirmed business objective exceeds its declared evidence bound.");
        }
        if (acceptedAtRevision < 0) {
            throw new IllegalArgumentException(
                    "A confirmed business objective requires a non-negative accepted revision.");
        }
        Objects.requireNonNull(evidenceTurnId, "evidenceTurnId");
    }

    /**
     * Validates provenance: the evidence quote must exist in the current user message
     * after only Unicode NFC normalization, line-ending normalization (CRLF→LF) and
     * whitespace normalization (consecutive whitespace collapsed to single space).
     *
     * <p>No semantic similarity or model boolean is used. Returns true only when the
     * normalized evidence is a substring of the normalized user message.
     */
    public static boolean provenanceValid(String evidence, String userMessage) {
        if (evidence == null || userMessage == null) {
            return false;
        }
        String normalizedEvidence = normalize(evidence);
        String normalizedMessage = normalize(userMessage);
        return normalizedMessage.contains(normalizedEvidence);
    }

    /**
     * Conservative normalization: Unicode NFC, CRLF→LF, consecutive whitespace collapsed
     * to a single space, and trimmed. This is the only normalization applied before
     * provenance validation — no semantic comparison, no case folding, no stemming.
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace("\r", "\n")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** A safe diagnostic: the accepted revision and evidence length, never the raw text. */
    @Override
    public String toString() {
        return "CampaignBusinessObjective[length=" + evidence.length()
                + ", acceptedAtRevision=" + acceptedAtRevision
                + ", evidenceTurnId=" + evidenceTurnId + ']';
    }

    /** Safe public status: only the length, never the raw objective. */
    public SafeObjectiveStatus safeStatus() {
        return new SafeObjectiveStatus(true, evidence.length(), acceptedAtRevision);
    }

    /**
     * A safe, non-sensitive representation of the confirmed objective for diagnostics,
     * public projections and HTTP responses. It exposes only status and length metadata,
     * never the raw evidence text.
     */
    public record SafeObjectiveStatus(
            boolean confirmed,
            int evidenceLength,
            long acceptedAtRevision
    ) {
        public SafeObjectiveStatus {
            if (evidenceLength < 0) {
                throw new IllegalArgumentException("Evidence length must be non-negative.");
            }
        }

        /** A status for when no objective has been confirmed. */
        public static SafeObjectiveStatus absent() {
            return new SafeObjectiveStatus(false, 0, -1L);
        }
    }
}