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
 * A deterministic, in-memory {@link ReferenceDataScopePort} that computes a real set intersection.
 *
 * <p>Each registered DataScope identifies a set of scope members, and an intersection is the common membership
 * of all three inputs — never a concatenation of identifiers and never a choice between them. The resulting
 * effective scope is described by a fresh identity derived from that common membership plus the canonical hash
 * of exactly those members, so a genuinely narrower scope is observably a different value from every input
 * while a caller scope nested inside the referenced scope legitimately keeps its own identity and hash.
 *
 * <p>The derived identity and hash are pure functions of the member set, so the same intersection always
 * produces the same effective scope regardless of the order the inputs or the members were supplied in. A
 * scope this port derives stays describable by it, so the same intersection can be taken again when a
 * descendant reference is resolved under a genuinely narrowed parent scope.
 *
 * <p>It is a test and local-use adapter, not a production security decision point.
 */
public final class InMemoryReferenceDataScopePort implements ReferenceDataScopePort {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final Map<String, Set<String>> membersByDataScope = new LinkedHashMap<>();

    /** Registers one DataScope and the exact scope members it stands for. */
    public InMemoryReferenceDataScopePort register(String dataScopeId, Set<String> members) {
        if (dataScopeId == null || members == null) {
            throw ReferenceException.invalid("A DataScope registration requires an identity and its members");
        }
        membersByDataScope.put(dataScopeId, new TreeSet<>(members));
        return this;
    }

    /** True when this port can describe the given DataScope identity. */
    public boolean knows(String dataScopeId) {
        return dataScopeId != null && membersByDataScope.containsKey(dataScopeId);
    }

    @Override
    public Optional<EffectiveDataScope> callerScope(
            String tenantId, String programId, String userId, String dataScopeId) {
        if (dataScopeId == null) {
            return Optional.empty();
        }
        Set<String> members = membersByDataScope.get(dataScopeId);
        if (members == null) {
            return Optional.empty();
        }
        return Optional.of(describe(dataScopeId, members));
    }

    @Override
    public Optional<EffectiveDataScope> intersect(
            EffectiveDataScope callerScope, EffectiveDataScope parentScope, EffectiveDataScope referencedScope) {
        if (callerScope == null || parentScope == null || referencedScope == null) {
            return Optional.empty();
        }
        Set<String> caller = membersByDataScope.get(callerScope.dataScopeId());
        Set<String> parent = membersByDataScope.get(parentScope.dataScopeId());
        Set<String> referenced = membersByDataScope.get(referencedScope.dataScopeId());
        if (caller == null || parent == null || referenced == null) {
            // A scope this port cannot describe is unknown, not empty: the caller fails closed on both, but
            // conflating them here would make an empty intersection indistinguishable from a missing one.
            return Optional.empty();
        }
        Set<String> common = new TreeSet<>(caller);
        common.retainAll(parent);
        common.retainAll(referenced);
        if (common.isEmpty()) {
            return Optional.empty();
        }
        String dataScopeId = derivedId(common, callerScope, parentScope, referencedScope);
        // A scope this port produced stays describable, so a genuinely narrowed parent scope can be
        // intersected again when a descendant reference is resolved under it. Without this, a derived
        // intersection would be indistinguishable from an unknown scope, and a legitimate nested reference
        // under a truly narrower parent would fail closed as an empty intersection.
        membersByDataScope.putIfAbsent(dataScopeId, common);
        return Optional.of(describe(dataScopeId, common));
    }

    /**
     * The identity of the intersection: an input's own identity when that input is exactly the common
     * membership, otherwise a fresh identity derived from the common membership alone.
     *
     * <p>Reusing an input's identity is correct exactly when the intersection equals that input — a caller
     * scope nested inside the referenced scope really is the intersection — and is never correct merely
     * because the input was supplied first.
     */
    private String derivedId(
            Set<String> common,
            EffectiveDataScope callerScope,
            EffectiveDataScope parentScope,
            EffectiveDataScope referencedScope) {
        for (EffectiveDataScope candidate : List.of(callerScope, parentScope, referencedScope)) {
            Set<String> members = membersByDataScope.get(candidate.dataScopeId());
            if (common.equals(members)) {
                return candidate.dataScopeId();
            }
        }
        return "ds-" + hashOf(sorted(common)).value()
                .substring("sha256:".length(), "sha256:".length() + 32);
    }

    private static EffectiveDataScope describe(String dataScopeId, Set<String> members) {
        return new EffectiveDataScope(dataScopeId, hashOf(sorted(members)));
    }

    private static List<String> sorted(Set<String> members) {
        List<String> ordered = new ArrayList<>(members);
        ordered.sort(String::compareTo);
        return ordered;
    }

    private static ContentHash hashOf(Object material) {
        return CANONICAL_JSON.hash(material);
    }
}