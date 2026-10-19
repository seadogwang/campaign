package com.company.campaign.evaluation.segment.reference;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * The wire spelling of one exact, immutable Audience Version pin inside a reference-corpus asset.
 *
 * <p>It carries exactly what a reference names and nothing else: the audience identity, the immutable version
 * identity and the exact content hash of that version. There is deliberately no {@code current}, {@code
 * latest} or name field, so a corpus asset cannot express a mutable pointer either.
 *
 * <p>It is a wire record rather than a reuse of {@link AudienceVersionRef} so the corpus tree never depends
 * on the reference core's Java type identity for its bytes, and so an unknown field in a case file is a
 * reported corpus defect rather than a silently ignored extra.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReferencePin(String audienceId, String versionId, ContentHash versionHash) {

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public ReferencePin {
        requireId(audienceId, "audienceId");
        requireId(versionId, "versionId");
        if (versionHash == null) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.PIN_INVALID,
                    "A reference pin requires the exact content hash of the version it names");
        }
    }

    /** The exact immutable pin as the reference core's own value. */
    public AudienceVersionRef toVersionRef() {
        return new AudienceVersionRef(audienceId, versionId, versionHash);
    }

    /** The bounded {@code audienceId/versionId} identity text, for deterministic ordering and diagnostics. */
    public String identityKey() {
        return audienceId + "/" + versionId;
    }

    private static void requireId(String value, String field) {
        if (value == null || !REFERENCE_ID.matcher(value).matches()) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.PIN_INVALID,
                    "A reference pin requires a bounded '" + field + "' identifier",
                    Map.of("field", field));
        }
    }
}