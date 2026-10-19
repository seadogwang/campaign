package com.company.campaign.metadata.service;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataException;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.MetadataTextNormalizer;
import com.company.campaign.metadata.domain.MetadataDefinition;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.repository.MetadataSnapshotRepository;
import com.company.campaign.security.authorization.AuthorizationDecision;
import com.company.campaign.security.authorization.AuthorizationRequest;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.authorization.ResourceNotFoundException;
import com.company.campaign.security.context.TrustedExecutionContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Authorized list/get/search over one retained, pinned metadata snapshot.
 *
 * <p>Every call takes an already-trusted {@link TrustedExecutionContext}: tenant, program, user and
 * DataScope are never query parameters. Definition ownership is read from the retained snapshot, and each
 * returned definition is authorized through the existing T02 {@link AuthorizationService} — there is no
 * parallel permission or DataScope mechanism. List and search filter out every definition that is not
 * explicitly allowed, including DataScope-unavailable decisions, so a denied definition cannot appear in a
 * result, a count or an ordering.
 */
public final class MetadataQueryService {

    /** Minimum accepted search result limit. */
    public static final int MIN_RESULT_LIMIT = 1;

    /** Maximum accepted search result limit. */
    public static final int MAX_RESULT_LIMIT = 100;

    /** Maximum accepted search query length in UTF-16 code units. */
    public static final int MAX_QUERY_LENGTH = 256;

    private static final Comparator<MetadataSearchMatch> MATCH_ORDER =
            Comparator.comparingInt((MetadataSearchMatch match) -> match.tier().ordinal())
                    .thenComparing(match -> match.definition().kind().ordinal())
                    .thenComparing(match -> match.definition().id());

    private final AuthorizationService authorizationService;
    private final MetadataSnapshotRepository repository;

    public MetadataQueryService(AuthorizationService authorizationService, MetadataSnapshotRepository repository) {
        this.authorizationService = Objects.requireNonNull(authorizationService, "authorizationService");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /** Lists permitted definitions of one kind, in the snapshot's canonical stable-ID order. */
    public List<MetadataDefinition> list(TrustedExecutionContext context, String snapshotId, MetadataKind kind) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(kind, "kind");
        return repository.findById(snapshotId)
                .map(snapshot -> permitted(context, snapshot, kind))
                .orElseGet(List::of);
    }

    /**
     * Gets one permitted definition.
     *
     * <p>A missing snapshot, a missing definition and any authorization denial all raise the identical
     * non-disclosing {@link ResourceNotFoundException}, so a caller cannot distinguish them.
     */
    public MetadataDefinition get(
            TrustedExecutionContext context, String snapshotId, MetadataKind kind, String definitionId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(kind, "kind");
        MetadataSnapshot snapshot = repository.findById(snapshotId).orElseThrow(ResourceNotFoundException::new);
        MetadataDefinition definition = find(snapshot, kind, definitionId);
        if (definition == null) {
            throw new ResourceNotFoundException();
        }
        authorizationService.requireAuthorized(AuthorizationRequest.of(
                context, kind.resourceKind(), definition.id(), snapshot.tenantId(), snapshot.programId()));
        return definition;
    }

    /** Searches permitted definitions of one kind with a strict bounded result limit. */
    public MetadataSearchResult search(
            TrustedExecutionContext context,
            String snapshotId,
            MetadataKind kind,
            String query,
            int limit) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(kind, "kind");
        String queryKey = requireQuery(query);
        requireLimit(limit);

        Optional<MetadataSnapshot> found = repository.findById(snapshotId);
        if (found.isEmpty()) {
            return new MetadataSearchResult(kind, snapshotId, limit, false, List.of());
        }

        List<MetadataSearchMatch> matches = new ArrayList<>();
        for (MetadataDefinition definition : permitted(context, found.get(), kind)) {
            MetadataSearchTier tier = tierFor(definition, queryKey);
            if (tier != null) {
                matches.add(new MetadataSearchMatch(definition, tier));
            }
        }
        matches.sort(MATCH_ORDER);
        boolean truncated = matches.size() > limit;
        List<MetadataSearchMatch> limited =
                truncated ? List.copyOf(matches.subList(0, limit)) : List.copyOf(matches);
        return new MetadataSearchResult(kind, snapshotId, limit, truncated, limited);
    }

    private List<MetadataDefinition> permitted(
            TrustedExecutionContext context, MetadataSnapshot snapshot, MetadataKind kind) {
        List<MetadataDefinition> permitted = new ArrayList<>();
        for (MetadataDefinition definition : snapshot.definitions(kind)) {
            AuthorizationDecision decision = authorizationService.authorize(AuthorizationRequest.of(
                    context, kind.resourceKind(), definition.id(), snapshot.tenantId(), snapshot.programId()));
            if (decision != null && decision.allowed()) {
                permitted.add(definition);
            }
        }
        return List.copyOf(permitted);
    }

    private static MetadataDefinition find(MetadataSnapshot snapshot, MetadataKind kind, String definitionId) {
        if (definitionId == null) {
            return null;
        }
        for (MetadataDefinition definition : snapshot.definitions(kind)) {
            if (definition.id().equals(definitionId)) {
                return definition;
            }
        }
        return null;
    }

    private static MetadataSearchTier tierFor(MetadataDefinition definition, String queryKey) {
        List<String> nameKeys = new ArrayList<>(definition.aliases().size() + 1);
        nameKeys.add(MetadataTextNormalizer.normalize(definition.displayName()));
        definition.aliases().forEach(alias -> nameKeys.add(MetadataTextNormalizer.normalize(alias)));
        String idKey = MetadataTextNormalizer.normalize(definition.id());

        if (idKey.equals(queryKey)) {
            return MetadataSearchTier.EXACT_ID;
        }
        if (nameKeys.contains(queryKey)) {
            return MetadataSearchTier.EXACT_NAME;
        }
        if (idKey.startsWith(queryKey) || nameKeys.stream().anyMatch(key -> key.startsWith(queryKey))) {
            return MetadataSearchTier.PREFIX;
        }
        if (idKey.contains(queryKey) || nameKeys.stream().anyMatch(key -> key.contains(queryKey))) {
            return MetadataSearchTier.CONTAINS;
        }
        return null;
    }

    private static String requireQuery(String query) {
        if (query == null || query.length() > MAX_QUERY_LENGTH) {
            throw new MetadataException(
                    MetadataErrorCodes.METADATA_SEARCH_QUERY_INVALID, "Search query is missing or too long");
        }
        String normalized = MetadataTextNormalizer.normalize(query);
        if (normalized.isEmpty()) {
            throw new MetadataException(
                    MetadataErrorCodes.METADATA_SEARCH_QUERY_INVALID, "Search query must not be blank");
        }
        return normalized;
    }

    private static void requireLimit(int limit) {
        if (limit < MIN_RESULT_LIMIT || limit > MAX_RESULT_LIMIT) {
            throw new MetadataException(
                    MetadataErrorCodes.METADATA_SEARCH_LIMIT_INVALID, "Search result limit is outside the supported range");
        }
    }
}