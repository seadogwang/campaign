package com.company.campaign.segment.reference;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * A deterministic, in-memory {@link AudienceMembershipPort} bound to exact version pins.
 *
 * <p>It is a fixture and local-execution dataset only. Membership is registered under the <em>full</em> pin,
 * so a set can never be read for one version and used for another, and a lookup for an unregistered pin
 * returns empty rather than an empty set — "we have no local membership for this version" and "this version
 * has no members" are different facts and must not be conflated.
 *
 * <p>The member-set hash is a canonical hash of the exact sorted member list together with the pin it belongs
 * to, so it changes if either the members or the version changes and it is a pure function of them. The
 * identifiers themselves never leave this class through any diagnostic channel: the value's own
 * {@code toString} deliberately prints only the version, the size and the hash.
 */
public final class FixtureAudienceMembershipDataset implements AudienceMembershipPort {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final Map<String, List<String>> membersByVersion = new LinkedHashMap<>();

    /** Registers the exact member identifiers of one immutable version. */
    public FixtureAudienceMembershipDataset register(AudienceVersionRef reference, Set<String> memberIds) {
        if (reference == null || memberIds == null) {
            throw ReferenceException.invalid("A membership registration requires an exact pin and its members");
        }
        Set<String> ordered = new TreeSet<>(memberIds);
        for (String memberId : ordered) {
            if (memberId == null || memberId.isBlank()) {
                throw ReferenceException.invalid("A member identifier must be a non-blank opaque string");
            }
        }
        membersByVersion.put(reference.pinKey(), List.copyOf(ordered));
        return this;
    }

    @Override
    public Optional<AudienceMembershipSet> lookup(
            AudienceVersionRef reference, EffectiveDataScope effectiveDataScope) {
        if (reference == null || effectiveDataScope == null) {
            return Optional.empty();
        }
        List<String> members = membersByVersion.get(reference.pinKey());
        if (members == null) {
            return Optional.empty();
        }
        return Optional.of(new AudienceMembershipSet(reference, members, hashOf(reference, members)));
    }

    /** The canonical hash of one version's exact member set; a pure function of the pin and the members. */
    public static ContentHash hashOf(AudienceVersionRef reference, List<String> members) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("audienceId", reference.audienceId());
        material.put("versionId", reference.versionId());
        material.put("versionHash", reference.versionHash().value());
        material.put("members", new ArrayList<>(members));
        return CANONICAL_JSON.hash(material);
    }
}