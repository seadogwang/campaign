package com.company.campaign.query.fixture;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable, versioned, content-hashed server-owned DataScope membership binding.
 *
 * <p>The mandatory DataScope predicate a plan carries names a host-resolved scope identifier only. This
 * binding is the one place that says which fixture members actually belong to that scope, and it is supplied
 * by the server alongside the dataset — never derived from the plan, the Segment DSL, a request or any
 * caller-controlled expression. A scope with no entry fails closed, so a permissive default is impossible.
 *
 * <p>Every entry is an immutable {@link MemberIdSet}, so membership is a sorted, duplicate-free set that is
 * evaluated before any user predicate.
 */
public final class FixtureScopeBinding {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    private final String bindingVersion;
    private final List<ScopeEntry> scopes;
    private final Map<String, MemberIdSet> scopesById;
    private final ContentHash bindingHash;

    private FixtureScopeBinding(String bindingVersion, List<ScopeEntry> scopes) {
        if (bindingVersion == null || !REFERENCE_ID.matcher(bindingVersion).matches()) {
            throw invalid("bindingVersion");
        }
        this.bindingVersion = bindingVersion;
        this.scopes = List.copyOf(Objects.requireNonNull(scopes, "scopes"));
        Map<String, MemberIdSet> index = new LinkedHashMap<>();
        for (ScopeEntry entry : this.scopes) {
            Objects.requireNonNull(entry, "scopeEntry");
            if (index.putIfAbsent(entry.dataScopeId(), entry.memberIds()) != null) {
                throw invalid("scopeEntryDuplicate");
            }
        }
        this.scopesById = Map.copyOf(index);
        this.bindingHash = CANONICAL_JSON.hash(new Material(
                bindingVersion,
                this.scopes.stream()
                        .map(entry -> entry.dataScopeId() + "|" + String.join(",", entry.memberIds().memberIds()))
                        .toList()));
    }

    /** Publishes a validated, content-hashed scope binding. */
    public static FixtureScopeBinding publish(String bindingVersion, List<ScopeEntry> scopes) {
        List<ScopeEntry> sorted = new ArrayList<>(scopes == null ? List.of() : scopes);
        sorted.sort(Comparator.comparing(ScopeEntry::dataScopeId));
        return new FixtureScopeBinding(bindingVersion, sorted);
    }

    /** The server-owned binding version pinned into every execution result. */
    public String bindingVersion() {
        return bindingVersion;
    }

    /** The canonical hash covering every scope entry. */
    public ContentHash bindingHash() {
        return bindingHash;
    }

    /** Every entry in deterministic scope-id order. */
    public List<ScopeEntry> scopes() {
        return scopes;
    }

    /** The members of one scope, or {@code null} when the server declares no such scope. */
    MemberIdSet scope(String dataScopeId) {
        return dataScopeId == null ? null : scopesById.get(dataScopeId);
    }

    /** One host-resolved scope identifier and the exact fixture members that belong to it. */
    public record ScopeEntry(String dataScopeId, MemberIdSet memberIds) {

        public ScopeEntry {
            if (dataScopeId == null || !REFERENCE_ID.matcher(dataScopeId).matches()) {
                throw invalid("scopeId");
            }
            Objects.requireNonNull(memberIds, "memberIds");
        }
    }

    private static FixtureException invalid(String invariant) {
        return new FixtureException(FixtureErrorCodes.FIXTURE_MAPPING_INVALID, invariant,
                "A server-owned DataScope binding is inconsistent and cannot be published.");
    }

    private record Material(String bindingVersion, List<String> scopes) {
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FixtureScopeBinding binding && Objects.equals(bindingHash, binding.bindingHash);
    }

    @Override
    public int hashCode() {
        return bindingHash.hashCode();
    }

    @Override
    public String toString() {
        return "FixtureScopeBinding[bindingVersion=" + bindingVersion + ", scopes=" + scopes.size()
                + ", bindingHash=" + bindingHash + ']';
    }
}