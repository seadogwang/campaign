package com.company.campaign.metadata.service;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.AttributeDefinition;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.CurrencySemantics;
import com.company.campaign.metadata.domain.MetadataDefinition;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.domain.NullSemantics;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.repository.InMemoryMetadataSnapshotRepository;
import com.company.campaign.metadata.repository.MetadataSnapshotRepository;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.security.authorization.DataScopeDecision;
import com.company.campaign.security.authorization.DataScopeException;
import com.company.campaign.security.authorization.DataScopePolicy;
import com.company.campaign.security.authorization.FailClosedAuthorizationService;
import com.company.campaign.security.authorization.PermissionDeniedException;
import com.company.campaign.security.authorization.ResourceKind;
import com.company.campaign.security.authorization.ResourceNotFoundException;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The T04 query service authorizes every returned definition through the T02 boundary and never discloses
 * a definition the caller cannot reach.
 */
class MetadataQueryServiceTest {

    private static final DataScopePolicy ALLOW_ALL = query -> DataScopeDecision.allowed();

    @Test
    void listReturnsOnlyPermittedDefinitionsOfTheRequestedKind() {
        MetadataQueryService service = service(ALLOW_ALL);
        TrustedExecutionContext context = MetadataFixtures.context(MetadataFixtures.allPermissions());

        assertThat(ids(service.list(context, "metadata-17", MetadataKind.ATTRIBUTE)))
                .containsExactly("member.tier");
        assertThat(ids(service.list(context, "metadata-17", MetadataKind.METRIC)))
                .containsExactly("order.net_paid_amount");
        assertThat(ids(service.list(context, "metadata-17", MetadataKind.RELATION)))
                .containsExactly("member.orders");
    }

    @Test
    void dataScopeDenialFiltersOneKindWithoutLeakingIt() {
        MetadataQueryService service = service(query -> switch (query.resourceKind()) {
            case ATTRIBUTE -> DataScopeDecision.denied("KIND_NOT_IN_SCOPE");
            default -> DataScopeDecision.allowed();
        });
        TrustedExecutionContext context = MetadataFixtures.context(MetadataFixtures.allPermissions());

        assertThat(service.list(context, "metadata-17", MetadataKind.ATTRIBUTE)).isEmpty();
        assertThat(service.list(context, "metadata-17", MetadataKind.METRIC)).hasSize(1);
    }

    @Test
    void listWithoutTheReadPermissionReturnsNothing() {
        MetadataQueryService service = service(ALLOW_ALL);
        TrustedExecutionContext context = MetadataFixtures.context(Set.of(CampaignPermission.CAMPAIGN_EDIT));

        assertThat(service.list(context, "metadata-17", MetadataKind.ATTRIBUTE)).isEmpty();
        assertThat(service.list(context, "metadata-17", MetadataKind.METRIC)).isEmpty();
        assertThat(service.list(context, "metadata-17", MetadataKind.RELATION)).isEmpty();
    }

    @Test
    void listFailsClosedWhenTheDataScopeIsUnavailable() {
        MetadataQueryService service = service(query -> DataScopeDecision.unavailable("SOURCE_DOWN"));
        TrustedExecutionContext context = MetadataFixtures.context(MetadataFixtures.allPermissions());

        assertThat(service.list(context, "metadata-17", MetadataKind.ATTRIBUTE)).isEmpty();
    }

    @Test
    void getReturnsAPermittedDefinition() {
        MetadataQueryService service = service(ALLOW_ALL);
        TrustedExecutionContext context = MetadataFixtures.context(MetadataFixtures.allPermissions());

        MetadataDefinition definition = service.get(context, "metadata-17", MetadataKind.ATTRIBUTE, "member.tier");
        assertThat(definition.kind()).isEqualTo(MetadataKind.ATTRIBUTE);
        assertThat(definition.displayName()).isEqualTo("Member Tier");
    }

    @Test
    void getFailsClosedForForeignMissingPermissionAndMissingDefinitions() {
        MetadataQueryService service = service(ALLOW_ALL);
        TrustedExecutionContext foreign =
                MetadataFixtures.context("tenant-999", "program-001", MetadataFixtures.allPermissions());
        TrustedExecutionContext withoutPermission =
                MetadataFixtures.context(Set.of(CampaignPermission.CAMPAIGN_EDIT));

        Throwable crossTenant = catchThrowable(() ->
                service.get(foreign, "metadata-17", MetadataKind.ATTRIBUTE, "member.tier"));
        Throwable missingSnapshot = catchThrowable(() ->
                service.get(MetadataFixtures.context(MetadataFixtures.allPermissions()),
                        "missing", MetadataKind.ATTRIBUTE, "member.tier"));
        Throwable missingDefinition = catchThrowable(() ->
                service.get(MetadataFixtures.context(MetadataFixtures.allPermissions()),
                        "metadata-17", MetadataKind.ATTRIBUTE, "member.unknown"));

        for (Throwable thrown : List.of(crossTenant, missingSnapshot, missingDefinition)) {
            assertThat(thrown).isInstanceOf(ResourceNotFoundException.class);
            assertThat(((ResourceNotFoundException) thrown).code())
                    .isEqualTo(com.company.campaign.shared.error.ContractErrorCodes.RESOURCE_NOT_FOUND);
            assertThat(thrown.getMessage() + ((ResourceNotFoundException) thrown).details())
                    .doesNotContain("tenant-999", "member.tier", "metadata-17");
        }

        Throwable denied = catchThrowable(() ->
                service.get(withoutPermission, "metadata-17", MetadataKind.ATTRIBUTE, "member.tier"));
        assertThat(denied).isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void getFailsClosedWhenTheDataScopeIsUnavailable() {
        MetadataQueryService service = service(query -> DataScopeDecision.unavailable("SOURCE_DOWN"));
        TrustedExecutionContext context = MetadataFixtures.context(MetadataFixtures.allPermissions());

        Throwable thrown = catchThrowable(() ->
                service.get(context, "metadata-17", MetadataKind.ATTRIBUTE, "member.tier"));
        assertThat(thrown).isInstanceOf(DataScopeException.class);
        assertThat(((DataScopeException) thrown).code())
                .isEqualTo(com.company.campaign.shared.error.ContractErrorCodes.DATASCOPE_UNAVAILABLE);
    }

    @Test
    void searchUsesDocumentedTiersWithStableTieBreakers() {
        MetadataQueryService service = searchService(false);
        TrustedExecutionContext context = MetadataFixtures.context(MetadataFixtures.allPermissions());

        MetadataSearchResult exactId = service.search(context, "metadata-17", MetadataKind.ATTRIBUTE,
                "search.name", 10);
        assertThat(exactId.matches()).extracting(match -> match.definition().id())
                .containsExactly("search.name");
        assertThat(exactId.matches().get(0).tier()).isEqualTo(MetadataSearchTier.EXACT_ID);

        MetadataSearchResult exactName = service.search(context, "metadata-17", MetadataKind.ATTRIBUTE,
                "name alias", 10);
        assertThat(exactName.matches()).extracting(match -> match.definition().id())
                .containsExactly("search.name");
        assertThat(exactName.matches().get(0).tier()).isEqualTo(MetadataSearchTier.EXACT_NAME);

        MetadataSearchResult contains = service.search(context, "metadata-17", MetadataKind.ATTRIBUTE,
                "target", 10);
        assertThat(contains.matches()).extracting(match -> match.tier())
                .containsOnly(MetadataSearchTier.CONTAINS);
        assertThat(contains.matches()).extracting(match -> match.definition().id())
                .containsExactly("search.contains", "search.prefix");

        MetadataSearchResult prefix = service.search(context, "metadata-17", MetadataKind.ATTRIBUTE,
                "search.", 10);
        assertThat(prefix.matches()).extracting(match -> match.tier())
                .containsOnly(MetadataSearchTier.PREFIX);
        assertThat(prefix.matches()).extracting(match -> match.definition().id())
                .containsExactly("search.contains", "search.exact", "search.name", "search.prefix");
    }

    @Test
    void searchYieldsAtMostOneExactIdMatchForACaseVariantQuery() {
        MetadataQueryService service = service(ALLOW_ALL);
        TrustedExecutionContext context = MetadataFixtures.context(MetadataFixtures.allPermissions());

        MetadataSearchResult attribute = service.search(
                context, "metadata-17", MetadataKind.ATTRIBUTE, "MEMBER.TIER", 10);
        assertThat(attribute.matches()).hasSize(1);
        assertThat(attribute.matches().get(0).tier()).isEqualTo(MetadataSearchTier.EXACT_ID);
        assertThat(attribute.matches().get(0).definition().id()).isEqualTo("member.tier");

        MetadataSearchResult metric = service.search(
                context, "metadata-17", MetadataKind.METRIC, "ORDER.NET_PAID_AMOUNT", 10);
        assertThat(metric.matches()).hasSize(1);
        assertThat(metric.matches().get(0).tier()).isEqualTo(MetadataSearchTier.EXACT_ID);
        assertThat(metric.matches().get(0).definition().id()).isEqualTo("order.net_paid_amount");
    }

    @Test
    void searchResultIsIndependentOfInsertionOrder() {
        MetadataQueryService forward = searchService(false);
        MetadataQueryService reversed = searchService(true);
        TrustedExecutionContext context = MetadataFixtures.context(MetadataFixtures.allPermissions());

        MetadataSearchResult a = forward.search(context, "metadata-17", MetadataKind.ATTRIBUTE, "target", 10);
        MetadataSearchResult b = reversed.search(context, "metadata-17", MetadataKind.ATTRIBUTE, "target", 10);
        assertThat(ids(a)).isEqualTo(ids(b));
    }

    @Test
    void searchHonoursTheResultLimitAndReportsTruncation() {
        MetadataQueryService service = searchService(false);
        TrustedExecutionContext context = MetadataFixtures.context(MetadataFixtures.allPermissions());

        MetadataSearchResult limited = service.search(context, "metadata-17", MetadataKind.ATTRIBUTE,
                "search.", 2);
        assertThat(limited.matches()).hasSize(2);
        assertThat(limited.truncated()).isTrue();
        assertThat(ids(limited)).containsExactly("search.contains", "search.exact");
    }

    @Test
    void searchNeverReturnsDeniedDefinitions() {
        MetadataQueryService service = searchService(false, denyKind(ResourceKind.ATTRIBUTE));
        TrustedExecutionContext context = MetadataFixtures.context(MetadataFixtures.allPermissions());

        MetadataSearchResult result = service.search(context, "metadata-17", MetadataKind.ATTRIBUTE,
                "search.", 10);
        assertThat(result.matches()).isEmpty();
    }

    @Test
    void invalidQueryAndLimitFailWithStableCodes() {
        MetadataQueryService service = searchService(false);
        TrustedExecutionContext context = MetadataFixtures.context(MetadataFixtures.allPermissions());

        ContractAssertions.assertFailsWithCode(() ->
                service.search(context, "metadata-17", MetadataKind.ATTRIBUTE, "   ", 10),
                MetadataErrorCodes.METADATA_SEARCH_QUERY_INVALID);
        ContractAssertions.assertFailsWithCode(() ->
                service.search(context, "metadata-17", MetadataKind.ATTRIBUTE, "x".repeat(257), 10),
                MetadataErrorCodes.METADATA_SEARCH_QUERY_INVALID);
        ContractAssertions.assertFailsWithCode(() ->
                service.search(context, "metadata-17", MetadataKind.ATTRIBUTE, "search", 0),
                MetadataErrorCodes.METADATA_SEARCH_LIMIT_INVALID);
        ContractAssertions.assertFailsWithCode(() ->
                service.search(context, "metadata-17", MetadataKind.ATTRIBUTE, "search", 101),
                MetadataErrorCodes.METADATA_SEARCH_LIMIT_INVALID);
    }

    @Test
    void searchIsNonDisclosingForMissingAndForeignSnapshots() {
        MetadataQueryService service = searchService(false);
        TrustedExecutionContext own = MetadataFixtures.context(MetadataFixtures.allPermissions());
        TrustedExecutionContext foreign =
                MetadataFixtures.context("tenant-999", "program-001", MetadataFixtures.allPermissions());

        MetadataSearchResult missing =
                service.search(own, "missing", MetadataKind.ATTRIBUTE, "search", 10);
        MetadataSearchResult crossTenant =
                service.search(foreign, "metadata-17", MetadataKind.ATTRIBUTE, "search", 10);

        assertThat(missing.matches()).isEmpty();
        assertThat(missing.truncated()).isFalse();
        assertThat(crossTenant.matches()).isEmpty();
        assertThat(crossTenant.truncated()).isFalse();
    }

    @Test
    void publishingANewerSnapshotDoesNotChangeOlderLookupResults() {
        InMemoryMetadataSnapshotRepository repository = new InMemoryMetadataSnapshotRepository();
        repository.publish(MetadataFixtures.standardSnapshot());
        MetadataQueryService service = new MetadataQueryService(
                new FailClosedAuthorizationService(ALLOW_ALL), repository);
        TrustedExecutionContext context = MetadataFixtures.context(MetadataFixtures.allPermissions());

        List<String> before = ids(service.list(context, "metadata-17", MetadataKind.ATTRIBUTE));
        MetadataDefinition beforeGet =
                service.get(context, "metadata-17", MetadataKind.ATTRIBUTE, "member.tier");

        repository.publish(MetadataFixtures.snapshot("metadata-18", "2",
                MetadataFixtures.TENANT, MetadataFixtures.PROGRAM,
                List.of(MetadataFixtures.snapshotAttribute("member.city", "Member City")),
                List.of(), List.of()));

        assertThat(ids(service.list(context, "metadata-17", MetadataKind.ATTRIBUTE))).isEqualTo(before);
        assertThat(service.get(context, "metadata-17", MetadataKind.ATTRIBUTE, "member.tier"))
                .isSameAs(beforeGet);
    }

    // --- fixtures -----------------------------------------------------------------------------------

    private static MetadataQueryService service(DataScopePolicy policy) {
        return service(policy, MetadataFixtures.standardSnapshot());
    }

    private static MetadataQueryService service(DataScopePolicy policy, MetadataSnapshot snapshot) {
        MetadataSnapshotRepository repository = new InMemoryMetadataSnapshotRepository();
        repository.publish(snapshot);
        return new MetadataQueryService(new FailClosedAuthorizationService(policy), repository);
    }

    private static DataScopePolicy denyKind(ResourceKind kind) {
        return query -> query.resourceKind() == kind
                ? DataScopeDecision.denied("KIND_NOT_IN_SCOPE")
                : DataScopeDecision.allowed();
    }

    private static MetadataQueryService searchService(boolean reversed) {
        return searchService(reversed, ALLOW_ALL);
    }

    private static MetadataQueryService searchService(boolean reversed, DataScopePolicy policy) {
        List<AttributeDefinition> attributes = List.of(
                searchAttribute("search.prefix", "Prefix Target", "prefix alias"),
                searchAttribute("search.contains", "Contains Target", "contains alias"),
                searchAttribute("search.exact", "Search Exact", "exact"),
                searchAttribute("search.name", "Search Name", "name alias"));
        if (reversed) {
            attributes = attributes.reversed();
        }
        MetadataSnapshot snapshot = MetadataFixtures.snapshot("metadata-17", "1",
                MetadataFixtures.TENANT, MetadataFixtures.PROGRAM, attributes, List.of(), List.of());
        return service(policy, snapshot);
    }

    private static AttributeDefinition searchAttribute(String id, String displayName, String alias) {
        return new AttributeDefinition(
                id,
                MetadataFixtures.memberVersion(),
                SubjectKind.MEMBER,
                displayName,
                SemanticDataType.STRING,
                MetadataFixtures.stringSemantics(),
                List.of(alias),
                List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL,
                        ComparisonOperator.IS_NULL, ComparisonOperator.IS_NOT_NULL),
                List.of(),
                NullSemantics.UNKNOWN_VALUE,
                CurrencySemantics.NOT_APPLICABLE,
                List.of(),
                List.of(),
                null);
    }

    private static List<String> ids(MetadataSearchResult result) {
        return result.matches().stream().map(match -> match.definition().id()).toList();
    }

    private static List<String> ids(List<MetadataDefinition> definitions) {
        return definitions.stream().map(MetadataDefinition::id).toList();
    }
}