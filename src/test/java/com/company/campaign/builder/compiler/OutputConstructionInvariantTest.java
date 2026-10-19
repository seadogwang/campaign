package com.company.campaign.builder.compiler;

import com.company.campaign.builder.proposal.ProposalMode;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.builder.testsupport.FixtureCapability;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.error.ContractException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.company.campaign.builder.testsupport.BuilderFixtures.DRAFT;
import static com.company.campaign.builder.testsupport.BuilderFixtures.SEGMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requirement 14 regression: the Manifest and Plan constructors re-prove their cross-invariants, so material
 * that carries a self-consistent hash but contradicts itself is still refused. These tests use the existing
 * package-private construction paths — no public factory and no reflection — exactly as the compiler does.
 */
class OutputConstructionInvariantTest {

    private static final String SEGMENT_VERSION = "segment-rule-based-1.0";
    private static final ContentHash CATALOG_HASH = ContentHash.sha256(
            "catalog".getBytes(StandardCharsets.UTF_8));
    private static final ContentHash POLICY_HASH = ContentHash.sha256(
            "policy".getBytes(StandardCharsets.UTF_8));
    private static final ContentHash PROPOSAL_HASH = ContentHash.sha256(
            "proposal".getBytes(StandardCharsets.UTF_8));

    private static final ResolvedComponent COMPONENT = BuilderFixtures.component(
            "component-a", SEGMENT, SEGMENT_VERSION, FixtureCapability.ordered("segment"), Map.of("spec", "a"));
    private static final List<CapabilityPin> PINS = List.of(new CapabilityPin(SEGMENT, SEGMENT_VERSION));

    // ------------------------------------------------------------------ manifest

    @Test
    void aWellFormedEditManifestAndPlanAreConstructible() {
        ResolvedCampaignManifest manifest = editManifest(null);
        CampaignPlan plan = plan(manifest, null);

        assertThat(manifest.campaignId()).isEqualTo(BuilderFixtures.CAMPAIGN);
        assertThat(plan.manifest()).isSameAs(manifest);
        assertThat(plan.campaignId()).isEqualTo(manifest.campaignId());
    }

    @Test
    void aCreateManifestIsConstructibleWithNoBaseFacts() {
        ResolvedCampaignManifest manifest = ResolvedCampaignManifest.create(
                CompilerVersions.MANIFEST_FORMAT_VERSION, CompilerVersions.COMPILER_VERSION, ProposalMode.CREATE,
                null, null, null, null, null, List.of(COMPONENT), PINS,
                BuilderFixtures.CATALOG_VERSION, CATALOG_HASH, BuilderFixtures.POLICY_VERSION, POLICY_HASH);

        assertThat(manifest.campaignId()).isNull();
        assertThat(manifest.baseCampaignRevision()).isNull();
        assertThat(manifest.baseDraftId()).isNull();
        assertThat(manifest.baseDraftRevision()).isNull();
        assertThat(manifest.forkBase()).isNull();
    }

    @Test
    void aCreateManifestThatCarriesBaseFactsIsRefused() {
        assertInconsistent(() -> ResolvedCampaignManifest.create(
                CompilerVersions.MANIFEST_FORMAT_VERSION, CompilerVersions.COMPILER_VERSION, ProposalMode.CREATE,
                BuilderFixtures.CAMPAIGN, BuilderFixtures.CAMPAIGN_REVISION, DRAFT, BuilderFixtures.DRAFT_REVISION,
                null, List.of(COMPONENT), PINS,
                BuilderFixtures.CATALOG_VERSION, CATALOG_HASH, BuilderFixtures.POLICY_VERSION, POLICY_HASH));
    }

    @Test
    void aCreateManifestThatCarriesAForkBaseIsRefused() {
        assertInconsistent(() -> ResolvedCampaignManifest.create(
                CompilerVersions.MANIFEST_FORMAT_VERSION, CompilerVersions.COMPILER_VERSION, ProposalMode.CREATE,
                null, null, null, null, publishedFork(),
                List.of(COMPONENT), PINS,
                BuilderFixtures.CATALOG_VERSION, CATALOG_HASH, BuilderFixtures.POLICY_VERSION, POLICY_HASH));
    }

    @Test
    void anEditManifestMissingOrContradictingItsBaseFactsIsRefused() {
        for (ManifestFault fault : ManifestFault.values()) {
            assertInconsistent(() -> editManifest(fault));
        }
    }

    // ------------------------------------------------------------------ plan

    @Test
    void aPlanThatDisagreesWithItsManifestOnAnySharedFactIsRefused() {
        for (PlanFault fault : PlanFault.values()) {
            assertInconsistent(() -> plan(editManifest(null), fault));
        }
    }

    @Test
    void aPlanWithUnorderedOrOverlongInstantsIsRefused() {
        ResolvedCampaignManifest manifest = editManifest(null);
        Instant compiledAt = BuilderFixtures.COMPILED_AT;
        assertInconsistent(() -> newPlan(manifest, compiledAt, compiledAt));
        assertInconsistent(() -> newPlan(manifest, compiledAt, compiledAt.plusSeconds(7200)));
    }

    // ------------------------------------------------------------------ helpers

    private enum ManifestFault {
        NULL_CAMPAIGN_ID,
        NON_CANONICAL_CAMPAIGN_ID,
        NULL_BASE_REVISION,
        NON_POSITIVE_BASE_REVISION,
        NULL_DRAFT_ID,
        NON_CANONICAL_DRAFT_ID,
        NULL_DRAFT_REVISION,
        NON_POSITIVE_DRAFT_REVISION,
        FORK_BASE_IS_THE_DRAFT
    }

    private enum PlanFault {
        CAMPAIGN_ID_NULL,
        CAMPAIGN_ID_OTHER,
        BASE_CAMPAIGN_REVISION,
        BASE_DRAFT_ID,
        BASE_DRAFT_REVISION,
        FORK_BASE,
        CAPABILITY_PINS,
        CATALOG_VERSION,
        CATALOG_HASH,
        POLICY_VERSION,
        POLICY_HASH
    }

    private static ResolvedCampaignManifest editManifest(ManifestFault fault) {
        String campaignId = BuilderFixtures.CAMPAIGN;
        Long baseRevision = BuilderFixtures.CAMPAIGN_REVISION;
        String draftId = DRAFT;
        Long draftRevision = BuilderFixtures.DRAFT_REVISION;
        PublishedIdentity forkBase = null;
        if (fault != null) {
            switch (fault) {
                case NULL_CAMPAIGN_ID -> campaignId = null;
                case NON_CANONICAL_CAMPAIGN_ID -> campaignId = "has spaces";
                case NULL_BASE_REVISION -> baseRevision = null;
                case NON_POSITIVE_BASE_REVISION -> baseRevision = 0L;
                case NULL_DRAFT_ID -> draftId = null;
                case NON_CANONICAL_DRAFT_ID -> draftId = "has spaces";
                case NULL_DRAFT_REVISION -> draftRevision = null;
                case NON_POSITIVE_DRAFT_REVISION -> draftRevision = 0L;
                case FORK_BASE_IS_THE_DRAFT -> forkBase = new PublishedIdentity(
                        DRAFT, 9L, ContentHash.sha256("published".getBytes(StandardCharsets.UTF_8)));
            }
        }
        return ResolvedCampaignManifest.create(
                CompilerVersions.MANIFEST_FORMAT_VERSION, CompilerVersions.COMPILER_VERSION, ProposalMode.EDIT,
                campaignId, baseRevision, draftId, draftRevision, forkBase, List.of(COMPONENT), PINS,
                BuilderFixtures.CATALOG_VERSION, CATALOG_HASH, BuilderFixtures.POLICY_VERSION, POLICY_HASH);
    }

    private static CampaignPlan plan(ResolvedCampaignManifest manifest, PlanFault fault) {
        String campaignId = manifest.campaignId();
        Long baseCampaignRevision = manifest.baseCampaignRevision();
        String baseDraftId = manifest.baseDraftId();
        Long baseDraftRevision = manifest.baseDraftRevision();
        PublishedIdentity forkBase = manifest.forkBase();
        List<CapabilityPin> pins = manifest.capabilityPins();
        String catalogVersion = manifest.catalogVersion();
        ContentHash catalogHash = manifest.catalogHash();
        String policyVersion = manifest.policyVersion();
        ContentHash policyHash = manifest.policyHash();
        if (fault != null) {
            switch (fault) {
                case CAMPAIGN_ID_NULL -> campaignId = null;
                case CAMPAIGN_ID_OTHER -> campaignId = "campaign-002";
                case BASE_CAMPAIGN_REVISION -> baseCampaignRevision = 99L;
                case BASE_DRAFT_ID -> baseDraftId = "draft-002";
                case BASE_DRAFT_REVISION -> baseDraftRevision = 99L;
                case FORK_BASE -> forkBase = publishedFork();
                case CAPABILITY_PINS -> pins = List.of(
                        new CapabilityPin("message.template", "message-template-1.0"));
                case CATALOG_VERSION -> catalogVersion = "catalog-99";
                case CATALOG_HASH -> catalogHash =
                        ContentHash.sha256("other-catalog".getBytes(StandardCharsets.UTF_8));
                case POLICY_VERSION -> policyVersion = "policy-99";
                case POLICY_HASH -> policyHash =
                        ContentHash.sha256("other-policy".getBytes(StandardCharsets.UTF_8));
            }
        }
        return CampaignPlan.create(
                CompilerVersions.PLAN_FORMAT_VERSION, CompilerVersions.COMPILER_VERSION,
                BuilderFixtures.COMPILED_AT, BuilderFixtures.EXPIRES_AT,
                BuilderFixtures.TENANT, BuilderFixtures.PROGRAM, BuilderFixtures.USER,
                campaignId, baseCampaignRevision, baseDraftId, baseDraftRevision, forkBase,
                PROPOSAL_HASH, manifest.manifestHash(),
                pins, catalogVersion, catalogHash, policyVersion, policyHash,
                manifest, List.of(), new SemanticDiff(List.of()),
                new ChangeSet(CompilerVersions.CHANGESET_FORMAT_VERSION, List.of()),
                List.of(), List.of(), RiskSummary.empty());
    }

    private static CampaignPlan newPlan(
            ResolvedCampaignManifest manifest, Instant compiledAt, Instant expiresAt) {
        return CampaignPlan.create(
                CompilerVersions.PLAN_FORMAT_VERSION, CompilerVersions.COMPILER_VERSION,
                compiledAt, expiresAt,
                BuilderFixtures.TENANT, BuilderFixtures.PROGRAM, BuilderFixtures.USER,
                manifest.campaignId(), manifest.baseCampaignRevision(), manifest.baseDraftId(),
                manifest.baseDraftRevision(), manifest.forkBase(),
                PROPOSAL_HASH, manifest.manifestHash(),
                manifest.capabilityPins(), manifest.catalogVersion(), manifest.catalogHash(),
                manifest.policyVersion(), manifest.policyHash(),
                manifest, List.of(), new SemanticDiff(List.of()),
                new ChangeSet(CompilerVersions.CHANGESET_FORMAT_VERSION, List.of()),
                List.of(), List.of(), RiskSummary.empty());
    }

    private static PublishedIdentity publishedFork() {
        return new PublishedIdentity(
                BuilderFixtures.PUBLISHED, 4L, ContentHash.sha256("published".getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertInconsistent(Runnable construction) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(ContractException.class)
                .satisfies(throwable -> {
                    ContractException failure = (ContractException) throwable;
                    assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_OUTPUT_INCONSISTENT);
                    assertThat(failure.getMessage())
                            .doesNotContain(BuilderFixtures.CAMPAIGN, DRAFT, SEGMENT, "component-a");
                    assertThat(failure.getCause()).isNull();
                });
    }
}