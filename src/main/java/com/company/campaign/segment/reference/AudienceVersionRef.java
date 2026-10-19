package com.company.campaign.segment.reference;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * An exact, immutable pin of one Audience Version: audience identity, version identity and version content
 * hash, with nothing else.
 *
 * <p>This is the only thing a reference ever names. There is deliberately no {@code current}, {@code latest},
 * {@code name} or {@code isLatest} member and no pointer field of any kind, so "whatever audience X currently
 * is" is unrepresentable rather than merely discouraged. All three parts are mandatory and every part is
 * validated, so a half-specified pin cannot be constructed and later silently widened.
 *
 * <p>The canonical order is audience identity, then version identity, then content hash, so a set of distinct
 * pins always serialises and hashes the same way regardless of the order they were discovered in.
 */
public record AudienceVersionRef(String audienceId, String versionId, ContentHash versionHash) {

    /** The deterministic order of distinct pins: audience, then version, then exact content hash. */
    public static final Comparator<AudienceVersionRef> ORDER =
            Comparator.comparing(AudienceVersionRef::audienceId)
                    .thenComparing(AudienceVersionRef::versionId)
                    .thenComparing(reference -> reference.versionHash().value());

    /** The identity of one version independent of its content hash: audience plus version ID. */
    public static final Comparator<AudienceVersionRef> IDENTITY_ORDER =
            Comparator.comparing(AudienceVersionRef::audienceId).thenComparing(AudienceVersionRef::versionId);

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public AudienceVersionRef {
        audienceId = requireId(audienceId, "audienceId");
        versionId = requireId(versionId, "versionId");
        if (versionHash == null) {
            throw ReferenceException.invalid("An audience version reference requires its exact content hash");
        }
    }

    /** True when both pins name the same audience version identity, ignoring the content hash. */
    public boolean sameIdentity(AudienceVersionRef other) {
        return other != null
                && audienceId.equals(other.audienceId)
                && versionId.equals(other.versionId);
    }

    /** The bounded {@code audienceId/versionId} identity text, used only for deterministic ordering keys. */
    public String identityKey() {
        return audienceId + "/" + versionId;
    }

    /**
     * The full pin text: identity plus the exact content hash.
     *
     * <p>It is the correct key whenever material must be bound to one exact version rather than to one version
     * <em>identity</em> — a membership set, for example, must never be readable through a pin whose bytes
     * differ, even though both pins name the same audience and version ID.
     */
    public String pinKey() {
        return identityKey() + "@" + versionHash.value();
    }

    private static String requireId(String value, String field) {
        if (value == null || !REFERENCE_ID.matcher(value).matches()) {
            throw ReferenceException.invalid("A bounded '" + field + "' identifier is required");
        }
        return value;
    }
}