package com.company.campaign.production.campaignagent;

import com.company.campaign.builder.agent.SegmentAgentEnvironment;
import com.company.campaign.builder.assembly.CampaignToolRunContext;
import com.company.campaign.builder.authoring.SegmentAuthoringIntegration;
import com.company.campaign.builder.campaignagent.CampaignAgentEnvironment;
import com.company.campaign.builder.campaignagent.CampaignBusinessResourceCatalog;
import com.company.campaign.builder.campaignagent.CampaignBusinessResourceTool;
import com.company.campaign.builder.campaignagent.http.CampaignAgentConversationController;
import com.company.campaign.builder.campaignagent.http.CampaignAgentConversationHistoryController;
import com.company.campaign.builder.campaignagent.http.CampaignAgentConversationHistoryService;
import com.company.campaign.builder.campaignagent.http.CampaignAgentConversationService;
import com.company.campaign.builder.campaignagent.http.CampaignAgentTurnController;
import com.company.campaign.builder.campaignagent.http.CampaignAgentTurnService;
import com.company.campaign.builder.compiler.CapabilityReadiness;
import com.company.campaign.builder.compiler.FrozenAuthoringPolicy;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.builder.segment.SegmentModelRequest;
import com.company.campaign.builder.skill.SegmentSkillDefinition;
import com.company.campaign.builder.tool.SegmentPreviewPort;
import com.company.campaign.campaignagent.CampaignAssemblyContextFactory;
import com.company.campaign.campaignagent.CampaignSegmentSourceProvider;
import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.conversation.CampaignConversationAuditRecord;
import com.company.campaign.conversation.CampaignConversationBoundary;
import com.company.campaign.conversation.CampaignConversationCreateKey;
import com.company.campaign.conversation.CampaignConversationHistoryStore;
import com.company.campaign.conversation.CampaignConversationIdentitySource;
import com.company.campaign.conversation.CampaignConversationKey;
import com.company.campaign.conversation.CampaignConversationReservation;
import com.company.campaign.conversation.CampaignConversationSnapshot;
import com.company.campaign.conversation.CampaignConversationStoreDurability;
import com.company.campaign.conversation.CampaignConversationSummary;
import com.company.campaign.conversation.CampaignConversationTransaction;
import com.company.campaign.conversation.DurableCampaignConversationAuditSink;
import com.company.campaign.conversation.DurableCampaignConversationStore;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.model.StructuredModelPort;
import com.company.campaign.model.StructuredModelRequest;
import com.company.campaign.production.testsupport.ProductionFailureCodes;
import com.company.campaign.security.context.MembershipStatus;
import com.company.campaign.security.context.PrincipalMembership;
import com.company.campaign.security.context.TrustedContextProvider;
import com.company.campaign.security.context.VerifiedIdentity;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T35B: the default-off production Campaign Agent host wires only production boundaries, is mutually exclusive
 * with the development host, and fails closed on every missing, ambiguous or incompatible dependency.
 *
 * <p>Every double here is a bounded test port; no test reads a real database, a real provider or the developer's
 * machine settings.
 */
class ProductionCampaignAgentConfigurationTest {

    private static final String[] ENABLED = {
            "campaign.production.enabled=true",
            "campaign.production.campaign-agent.enabled=true",
    };

    private static ApplicationContextRunner enabled() {
        return new ApplicationContextRunner()
                .withUserConfiguration(ProductionCampaignAgentConfiguration.class)
                .withBean(CampaignBusinessResourceTool.class,
                        () -> (tenantId, programId) -> CampaignBusinessResourceCatalog.empty())
                .withPropertyValues(ENABLED);
    }

    /** The trusted provider, the one model, and the one durable audit sink; no store, no Preview, no assets. */
    private static ApplicationContextRunner minimal() {
        return enabled()
                .withBean("campaignProductionTrustedContextProvider",
                        TrustedContextProvider.class, FakeIdentity::new)
                .withBean("campaignProductionStructuredModel", StructuredModelPort.class, FakeModel::new)
                .withBean(DurableCampaignConversationAuditSink.class,
                        () -> (CampaignConversationAuditRecord record) -> { });
    }

    private static ApplicationContextRunner withStore(ApplicationContextRunner runner) {
        return runner.withBean(DurableCampaignConversationStore.class, FakeDurableStore::new);
    }

    /**
     * A host store with the accepted PostgreSQL claim that cannot enumerate its own conversations.
     *
     * <p>It is durable and accepted, so it satisfies the durability requirement, but it deliberately does not
     * implement the read-only history port, so the production host must still fail closed on the history service.
     */
    private static ApplicationContextRunner withMutationOnlyStore(ApplicationContextRunner runner) {
        return runner.withBean(DurableCampaignConversationStore.class, MutationOnlyDurableStore::new);
    }

    private static ApplicationContextRunner withPreview(ApplicationContextRunner runner) {
        return runner.withBean(SegmentPreviewPort.class, () -> (document, presentation) -> null);
    }

    private static ApplicationContextRunner withAssets(ApplicationContextRunner runner) {
        return runner.withBean(ProductionCampaignAssets.class, FakeAssets::new);
    }

    private static ApplicationContextRunner withHostSegmentSource(ApplicationContextRunner runner) {
        return runner.withBean(CampaignSegmentSourceProvider.class, CampaignSegmentSourceProvider::none);
    }

    private static ApplicationContextRunner wired() {
        return withHostSegmentSource(withAssets(withPreview(withStore(minimal()))));
    }

    // ------------------------------------------------------------------ inertness and exclusion

    @Test
    void theDefaultApplicationCreatesNoProductionHostAtAll() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProductionCampaignAgentConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(CampaignAgentEnvironment.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(CampaignAgentTurnService.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(CampaignAgentConversationService.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(DurableCampaignConversationStore.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(CampaignConversationIdentitySource.class)).isEmpty();
                });
    }

    @Test
    void theHostStaysInertWithoutTheProductionBoundary() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProductionCampaignAgentConfiguration.class)
                .withPropertyValues("campaign.production.campaign-agent.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(CampaignAgentEnvironment.class)).isEmpty();
                });
    }

    @Test
    void theDevelopmentAndProductionHostsAreMutuallyExclusive() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProductionCampaignAgentConfiguration.class)
                .withPropertyValues(ENABLED)
                .withPropertyValues("campaign.agent.development.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(CampaignAgentEnvironment.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(CampaignAgentTurnService.class)).isEmpty();
                });
    }

    // ------------------------------------------------------------------ fail-closed dependencies

    @Test
    void aMissingDurableStoreFailsClosed() {
        withHostSegmentSource(withAssets(withPreview(minimal()))).run(context -> {
            assertThat(context).hasFailed();
            assertThat(ProductionFailureCodes.codeOf(context.getStartupFailure()))
                    .isEqualTo(ProductionCampaignAgentErrorCodes.DURABLE_STORE_REQUIRED);
        });
    }

    @Test
    void aHostSuppliedStoreWithTheAcceptedPostgresqlClaimSatisfiesTheDurableStoreRequirement() {
        wired().run(context -> {
            assertThat(context).hasNotFailed();
            DurableCampaignConversationStore store = context.getBean(
                    ProductionCampaignAgentConfiguration.STORE_BEAN_NAME,
                    DurableCampaignConversationStore.class);
            assertThat(store.durability()).isEqualTo(CampaignConversationStoreDurability.POSTGRESQL);
            assertThat(store.durability().accepted()).isTrue();
        });
    }

    @Test
    void anInMemoryStyleStoreCannotSatisfyTheDurableStoreRequirement() {
        // The in-memory-style store is registered through its visible durable type and still declares an
        // in-memory claim, so it fails on the claim rather than by hiding behind a narrower Spring type.
        assertStoreIsRejected(new InMemoryStyleStore());
    }

    @Test
    void aStoreWithoutAnyDurabilityClaimFailsClosed() {
        assertStoreIsRejected(new NoClaimStore());
    }

    @Test
    void aDevelopmentOnlyDurabilityClaimFailsClosed() {
        assertStoreIsRejected(new DeclaredClaimStore(CampaignConversationStoreDurability.DEVELOPMENT_ONLY));
    }

    @Test
    void anUnverifiedDurabilityClaimFailsClosed() {
        assertStoreIsRejected(new DeclaredClaimStore(CampaignConversationStoreDurability.UNVERIFIED));
    }

    private static void assertStoreIsRejected(DurableCampaignConversationStore store) {
        withHostSegmentSource(withAssets(withPreview(
                minimal().withBean(DurableCampaignConversationStore.class, () -> store)))).run(context -> {
            assertThat(context).hasFailed();
            assertThat(ProductionFailureCodes.codeOf(context.getStartupFailure()))
                    .isEqualTo(ProductionCampaignAgentErrorCodes.DURABLE_STORE_REQUIRED);
        });
    }

    @Test
    void anAcceptedDurableStoreThatCannotServeHistoryFailsClosed() {
        // The store passes the durability claim but cannot enumerate its own conversations, so the read-only
        // history service must abort the start rather than render an invented or empty history list.
        withHostSegmentSource(withAssets(withPreview(withMutationOnlyStore(minimal())))).run(context -> {
            assertThat(context).hasFailed();
            assertThat(ProductionFailureCodes.codeOf(context.getStartupFailure()))
                    .isEqualTo(ProductionCampaignAgentErrorCodes.HISTORY_STORE_REQUIRED);
        });
    }

    @Test
    void theAcceptedProductionAdapterProvidesBothMutationAndHistoryCapabilities() {
        wired().run(context -> {
            assertThat(context).hasNotFailed();
            DurableCampaignConversationStore store = context.getBean(
                    ProductionCampaignAgentConfiguration.STORE_BEAN_NAME,
                    DurableCampaignConversationStore.class);
            assertThat(store).isInstanceOf(CampaignConversationHistoryStore.class);
            assertThat(context.getBeanNamesForType(CampaignAgentConversationHistoryService.class)).hasSize(1);
            assertThat(context.getBean(CampaignAgentConversationHistoryController.class)).isNotNull();
        });
    }

    @Test
    void aMissingDurableAuditSinkFailsClosed() {
        withHostSegmentSource(withAssets(withPreview(withStore(
                enabled()
                        .withBean("campaignProductionTrustedContextProvider",
                                TrustedContextProvider.class, FakeIdentity::new)
                        .withBean("campaignProductionStructuredModel",
                                StructuredModelPort.class, FakeModel::new))))).run(context -> {
            assertThat(context).hasFailed();
            assertThat(ProductionFailureCodes.codeOf(context.getStartupFailure()))
                    .isEqualTo(ProductionCampaignAgentErrorCodes.DURABLE_AUDIT_REQUIRED);
        });
    }

    @Test
    void aMissingPreviewPortFailsClosed() {
        withHostSegmentSource(withAssets(withStore(minimal()))).run(context -> {
            assertThat(context).hasFailed();
            assertThat(ProductionFailureCodes.codeOf(context.getStartupFailure()))
                    .isEqualTo(ProductionCampaignAgentErrorCodes.PREVIEW_REQUIRED);
        });
    }

    @Test
    void aMissingCampaignAssetPortFailsClosed() {
        withHostSegmentSource(withPreview(withStore(minimal()))).run(context -> {
            assertThat(context).hasFailed();
            assertThat(ProductionFailureCodes.codeOf(context.getStartupFailure()))
                    .isEqualTo(ProductionCampaignAgentErrorCodes.ASSETS_REQUIRED);
        });
    }

    @Test
    void aSegmentAssetPortWithoutUsableAssetsFailsClosed() {
        withAssets(withPreview(withStore(minimal())))
                .withBean(ProductionSegmentAssets.class, FakeSegmentAssets::new)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(ProductionFailureCodes.codeOf(context.getStartupFailure()))
                            .isEqualTo(ProductionCampaignAgentErrorCodes.SEGMENT_ASSETS_REQUIRED);
                });
    }

    @Test
    void aModelThatCannotServeSegmentIntentFailsClosed() {
        withAssets(withPreview(withStore(enabled()
                .withBean("campaignProductionTrustedContextProvider",
                        TrustedContextProvider.class, FakeIdentity::new)
                .withBean("campaignProductionStructuredModel", StructuredModelPort.class, PlainModel::new)
                .withBean(DurableCampaignConversationAuditSink.class,
                        () -> (CampaignConversationAuditRecord record) -> { })))).run(context -> {
            assertThat(context).hasFailed();
            assertThat(ProductionFailureCodes.codeOf(context.getStartupFailure()))
                    .isEqualTo(ProductionCampaignAgentErrorCodes.SEGMENT_MODEL_TRANSPORT_INCOMPATIBLE);
        });
    }

    // ------------------------------------------------------------------ the wired host

    @Test
    void theEnabledHostWiresOnlyTheAcceptedProductionServicesAndRoutes() {
        wired().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(CampaignAgentEnvironment.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(CampaignAgentTurnService.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(CampaignAgentConversationService.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(DurableCampaignConversationStore.class))
                    .contains(ProductionCampaignAgentConfiguration.STORE_BEAN_NAME);
            assertThat(context.getBeanNamesForType(DurableCampaignConversationAuditSink.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(CampaignConversationIdentitySource.class)).hasSize(1);
            assertThat(context.getBean(CampaignAgentConversationController.class)).isNotNull();
            assertThat(context.getBean(CampaignAgentTurnController.class)).isNotNull();
            assertThat(context.getBean(CampaignAgentConversationHistoryController.class)).isNotNull();
            assertThat(context.getBeanNamesForType(CampaignAgentConversationHistoryService.class)).hasSize(1);
            assertThat(context.getBean(CampaignAgentConversationService.class))
                    .isInstanceOf(com.company.campaign.campaignagent.CampaignConversationCreateService.class);
            assertThat(context.getBean(CampaignAgentTurnService.class))
                    .isInstanceOf(com.company.campaign.campaignagent.CampaignConversationTurnService.class);
            assertThat(context.getBean(CampaignAgentEnvironment.class).toString()).contains("DEVELOPMENT_ONLY");
        });
    }

    @Test
    void onlyOneProductionStructuredModelServesTheHost() {
        wired().run(context -> {
            assertThat(context.getBeanNamesForType(StructuredModelPort.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(SegmentIntentModelPort.class)).hasSize(1);
            assertThat(context.getBean(StructuredModelPort.class))
                    .isSameAs(context.getBean(SegmentIntentModelPort.class));
        });
    }

    // ------------------------------------------------------------------ test doubles

    /** A bounded fake trusted-context provider; it issues no token and verifies nothing. */
    static final class FakeIdentity implements TrustedContextProvider {

        @Override
        public VerifiedIdentity verifiedIdentity() {
            return new VerifiedIdentity(
                    "user-prod", "https://issuer.invalid", Instant.parse("2026-01-01T00:00:00Z"));
        }

        @Override
        public PrincipalMembership verifiedMembership() {
            return new PrincipalMembership("membership-prod", "user-prod", "tenant-prod", "program-prod",
                    "scope-prod", MembershipStatus.ACTIVE, Set.of());
        }

        @Override
        public TrustedExecutionContextRef executionReference() {
            return new TrustedExecutionContextRef("tenant-prod", "program-prod", "user-prod", "scope-prod",
                    "snapshot-prod", LocaleTag.of(Locale.US), ZoneId.of("UTC"), "USD",
                    Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2025-12-31T23:59:00Z"));
        }
    }

    /** One model object that serves both the neutral port and the Segment intent port. */
    static final class FakeModel implements StructuredModelPort, SegmentIntentModelPort {

        @Override
        public byte[] invokeStructured(StructuredModelRequest request) {
            return "{}".getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] invoke(SegmentModelRequest request) {
            return "{}".getBytes(StandardCharsets.UTF_8);
        }
    }

    /** A model that cannot serve Segment intent, so the real Segment source cannot be built from it. */
    static final class PlainModel implements StructuredModelPort {

        @Override
        public byte[] invokeStructured(StructuredModelRequest request) {
            return "{}".getBytes(StandardCharsets.UTF_8);
        }
    }

    /** A durable store double whose conversation behavior is never reached and whose claim is what a test varies. */
    abstract static class ClaimedDurableStore implements DurableCampaignConversationStore {

        @Override
        public CampaignConversationTransaction open(CampaignConversationKey key) {
            throw new UnsupportedOperationException("not used by this wiring test");
        }

        @Override
        public CampaignConversationReservation reserve(
                CampaignConversationCreateKey createKey,
                CampaignConversationKey requestedKey,
                ContentHash requestFingerprint) {
            throw new UnsupportedOperationException("not used by this wiring test");
        }
    }

    /**
     * The host-supplied durable test double of the accepted adapter: it exposes the PostgreSQL claim and the
     * read-only history capability, so it satisfies both the durable-store and the history-store requirement.
     *
     * <p>Its conversation behavior is never reached by a wiring test, so both operations fail loudly rather than
     * inventing a result.
     */
    static final class FakeDurableStore extends ClaimedDurableStore implements CampaignConversationHistoryStore {

        @Override
        public CampaignConversationStoreDurability durability() {
            return CampaignConversationStoreDurability.POSTGRESQL;
        }

        @Override
        public List<CampaignConversationSummary> list(CampaignConversationBoundary boundary, int limit) {
            throw new UnsupportedOperationException("not used by this wiring test");
        }

        @Override
        public CampaignConversationSnapshot read(CampaignConversationKey key) {
            throw new UnsupportedOperationException("not used by this wiring test");
        }
    }

    /** A durable-typed store with the accepted claim that deliberately omits the read-only history capability. */
    static final class MutationOnlyDurableStore extends ClaimedDurableStore {

        @Override
        public CampaignConversationStoreDurability durability() {
            return CampaignConversationStoreDurability.POSTGRESQL;
        }
    }

    /** An in-memory-style registry that implements the durable type but declares no real durability. */
    static final class InMemoryStyleStore extends ClaimedDurableStore {

        @Override
        public CampaignConversationStoreDurability durability() {
            return CampaignConversationStoreDurability.IN_MEMORY;
        }
    }

    /** A durable-typed store that exposes no durability claim at all. */
    static final class NoClaimStore extends ClaimedDurableStore {

        @Override
        public CampaignConversationStoreDurability durability() {
            return null;
        }
    }

    /** A durable-typed store whose present but non-accepted claim is the one a rejection test names. */
    static final class DeclaredClaimStore extends ClaimedDurableStore {

        private final CampaignConversationStoreDurability claim;

        private DeclaredClaimStore(CampaignConversationStoreDurability claim) {
            this.claim = claim;
        }

        @Override
        public CampaignConversationStoreDurability durability() {
            return claim;
        }
    }

    /** A production asset port with the bounds this wiring test needs. */
    static final class FakeAssets implements ProductionCampaignAssets {

        @Override
        public CampaignToolRunContext toolRunContext(
                com.company.campaign.security.context.TrustedExecutionContext context) {
            return null;
        }

        @Override
        public SegmentAuthoringIntegration segmentIntegration() {
            return null;
        }

        @Override
        public FrozenCapabilityCatalog capabilityCatalog() {
            return null;
        }

        @Override
        public FrozenAuthoringPolicy authoringPolicy() {
            return null;
        }

        @Override
        public CapabilityReadiness segmentReadiness() {
            return null;
        }

        @Override
        public CampaignAssemblyContextFactory assemblyContextFactory() {
            return (key, context) -> null;
        }

        @Override
        public ProductionAssetProvenance provenance() {
            return ProductionAssetProvenance.DEVELOPMENT_ONLY;
        }
    }

    /** A Segment asset port with no committed assets, so no real Segment source can be built. */
    static final class FakeSegmentAssets implements ProductionSegmentAssets {

        @Override
        public SegmentSkillDefinition segmentSkill() {
            return null;
        }

        @Override
        public MetadataSnapshot snapshot() {
            return null;
        }

        @Override
        public SegmentAgentEnvironment segmentAgentEnvironment(
                SegmentIntentModelPort modelPort,
                SegmentPreviewPort previewPort,
                TrustedContextProvider identity) {
            return null;
        }
    }
}
