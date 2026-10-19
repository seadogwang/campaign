package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.CapabilityPin;
import com.company.campaign.builder.compiler.DraftIdentity;
import com.company.campaign.builder.compiler.PublishedIdentity;
import com.company.campaign.lifecycle.testsupport.PublishScenario;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T17 domain contracts: every publish-phase artifact is immutable, self-binding and canonically hashed, and a
 * preparation's four lifecycle states are decidable from its own frozen facts.
 */
class PublishContractIntegrityTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-03T00:05:00Z");

    // ------------------------------------------------------------------ preparation state

    @Test
    void unexpiredUnconsumedPreparationStateIsPrepared() {
        PublishPreparation preparation = preparation(Duration.ofMinutes(15));

        assertThat(preparation.durableState(CREATED_AT, false)).isEqualTo(PublishPreparationState.PREPARED);
        assertThat(preparation.durableState(CREATED_AT.plus(Duration.ofMinutes(14)), false))
                .isEqualTo(PublishPreparationState.PREPARED);
        assertThat(preparation.durableState(CREATED_AT, false).publishable()).isTrue();
    }

    @Test
    void consumedOutranksExpiryAndExpiryOutranksPrepared() {
        PublishPreparation preparation = preparation(Duration.ofMinutes(15));
        Instant longAfterExpiry = CREATED_AT.plus(Duration.ofDays(1));

        assertThat(preparation.durableState(longAfterExpiry, true))
                .as("a consumed preparation is consumed forever")
                .isEqualTo(PublishPreparationState.CONSUMED);
        assertThat(preparation.durableState(preparation.expiresAt(), false))
                .as("a preparation is expired at its expiry instant, not after it")
                .isEqualTo(PublishPreparationState.EXPIRED);
        assertThat(preparation.durableState(longAfterExpiry, false))
                .isEqualTo(PublishPreparationState.EXPIRED);
        assertThat(PublishPreparationState.CONSUMED.publishable()).isFalse();
        assertThat(PublishPreparationState.EXPIRED.publishable()).isFalse();
        assertThat(PublishPreparationState.STALE.publishable()).isFalse();
    }

    @Test
    void everyStateOtherThanPreparedIsUnpublishable() {
        assertThat(PublishPreparationState.values())
                .containsExactly(PublishPreparationState.PREPARED, PublishPreparationState.CONSUMED,
                        PublishPreparationState.EXPIRED, PublishPreparationState.STALE);
        assertThat(List.of(PublishPreparationState.values()).stream()
                .filter(PublishPreparationState::publishable).toList())
                .containsExactly(PublishPreparationState.PREPARED);
    }

    // ------------------------------------------------------------------ canonical checksums

    @Test
    void aPreparationWhoseBytesDoNotMatchItsOwnHashIsNotRepresentable() {
        PublishPreparation preparation = preparation(Duration.ofMinutes(15));

        assertThatThrownBy(() -> new PublishPreparation(
                preparation.formatVersion(), preparation.preparationId(),
                preparation.tenantId(), preparation.programId(), preparation.userId(),
                preparation.dataScopeId(), preparation.campaignId(), preparation.campaignRevision(),
                preparation.draftId(), preparation.draftRevision(), preparation.forkBase(),
                preparation.planId(), preparation.planHash(), preparation.manifestHash(),
                preparation.draftHash(), preparation.catalogVersion(), preparation.catalogHash(),
                preparation.policyVersion(), preparation.policyHash(), preparation.capabilityPins(),
                preparation.previewCards(), preparation.requiredRiskCodes(),
                preparation.readinessCapability(), preparation.readinessState(),
                preparation.gateInputsHash(), preparation.approvedDecisionHash(),
                preparation.createdAt(), preparation.expiresAt(),
                otherHash("tampered")))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_INTERNAL_ERROR);
                    assertThat(failure.invariant()).isEqualTo("preparationHash");
                });
    }

    @Test
    void aPreparationLifetimeOutsideItsBoundIsNotRepresentable() {
        assertThatThrownBy(() -> preparation(Duration.ofSeconds(5)))
                .as("a preparation may not be born spent")
                .isInstanceOf(LifecycleException.class);
        assertThatThrownBy(() -> preparation(Duration.ofMinutes(61)))
                .as("a preparation may not outlive its own inputs")
                .isInstanceOf(LifecycleException.class);
    }

    @Test
    void aReceiptWhoseBytesDoNotMatchItsOwnHashIsNotRepresentable() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult preparation = scenario.prepare();
        scenario.confirm(preparation, "key-1", List.of());
        PublishReceipt receipt = scenario.store.findPublishReceipt(
                scenario.context.tenantId(), scenario.context.programId(), scenario.context.userId(),
                preparation.preparationId(), "key-1").orElseThrow();

        assertThatThrownBy(() -> new PublishReceipt(
                receipt.formatVersion(), receipt.receiptId(), receipt.tenantId(), receipt.programId(),
                receipt.userId(), receipt.dataScopeId(), receipt.preparationId(), receipt.preparationHash(),
                receipt.idempotencyKey(), receipt.requestHash(), receipt.publishedId(),
                receipt.publishedRevision(), receipt.campaignId(), receipt.campaignRevision(),
                receipt.draftId(), receipt.draftRevision(), receipt.planId(), receipt.planHash(),
                receipt.manifestHash(), receipt.contentChecksum(), receipt.confirmedRiskCodes(),
                receipt.publishedAt(), otherHash("tampered")))
                .isInstanceOfSatisfying(LifecycleException.class,
                        failure -> assertThat(failure.invariant()).isEqualTo("receiptHash"));
    }

    /**
     * R1 requirement 7: the internal receipt stays server side, and the outward publish result carries no
     * tenant, program, user or DataScope value.
     */
    @Test
    void theOutwardPublishResultCarriesNoTrustedOwnershipIdentifier() {
        PublishScenario scenario = PublishScenario.active();
        PublishResult result = scenario.confirm(scenario.prepare(), "key-1", List.of());

        assertThat(scenario.store.findPublished(result.publishedId()).orElseThrow().tenantId())
                .as("the internal definition does carry the trusted owner")
                .isNotNull();
        assertThat(result.toString())
                .doesNotContain(scenario.context.tenantId(), scenario.context.programId(),
                        scenario.context.userId(), scenario.context.dataScopeId());
    }

    @Test
    void aDefinitionWhoseContentDoesNotMatchItsOwnChecksumIsNotRepresentable() {
        PublishScenario scenario = PublishScenario.active();
        PublishedCampaignDefinition definition = scenario.store
                .findPublished(scenario.confirm(scenario.prepare(), "key-1", List.of()).publishedId())
                .orElseThrow();

        assertThatThrownBy(() -> new PublishedCampaignDefinition(
                definition.formatVersion(), definition.publishedId(), definition.publishedRevision(),
                definition.tenantId(), definition.programId(), definition.userId(),
                definition.dataScopeId(), definition.campaignId(), definition.campaignRevision(),
                definition.sourceDraftId(), definition.sourceDraftRevision(), definition.forkBase(),
                definition.planId(), definition.planHash(), definition.manifestHash(),
                definition.draftHash(), definition.catalogVersion(), definition.catalogHash(),
                definition.policyVersion(), definition.policyHash(), definition.capabilityPins(),
                definition.previewCards(), definition.riskCodes(), definition.readinessCapability(),
                definition.readinessState(), definition.gateInputsHash(), definition.approvedDecisionHash(),
                definition.manifest(), definition.publishedAt(), otherHash("tampered")))
                .isInstanceOfSatisfying(LifecycleException.class,
                        failure -> assertThat(failure.invariant()).isEqualTo("contentChecksum"));
    }

    @Test
    void thePublishedIdentityBindsTheContentChecksum() {
        PublishScenario scenario = PublishScenario.active();
        PublishedCampaignDefinition definition = scenario.store
                .findPublished(scenario.confirm(scenario.prepare(), "key-1", List.of()).publishedId())
                .orElseThrow();

        PublishedIdentity identity = definition.identity();
        assertThat(identity.publishedId()).isEqualTo(definition.publishedId());
        assertThat(identity.revision()).isEqualTo(definition.publishedRevision());
        assertThat(identity.contentHash()).isEqualTo(definition.contentChecksum());
    }

    // ------------------------------------------------------------------ request hashes

    @Test
    void theRequestHashIsStableUnderConfirmationOrderAndChangesWithTheRequest() {
        ContentHash hash = otherHash("preparation");
        PublishCommand first = new PublishCommand("preparation-1", hash, "key-1",
                List.of("HIGH_REACH", "SEGMENT_SAMPLE_EXPOSED"));
        PublishCommand reordered = new PublishCommand("preparation-1", hash, "key-1",
                List.of("SEGMENT_SAMPLE_EXPOSED", "HIGH_REACH"));
        PublishCommand otherKey = new PublishCommand("preparation-1", hash, "key-2",
                List.of("HIGH_REACH", "SEGMENT_SAMPLE_EXPOSED"));
        PublishCommand otherRisks = new PublishCommand("preparation-1", hash, "key-1",
                List.of("HIGH_REACH"));

        assertThat(reordered.requestHash()).isEqualTo(first.requestHash());
        assertThat(otherKey.requestHash()).isNotEqualTo(first.requestHash());
        assertThat(otherRisks.requestHash()).isNotEqualTo(first.requestHash());
    }

    /**
     * R1 requirement 6: the canonical request hash distinguishes every structurally different accepted
     * command, so a repeated acknowledgement can never collapse onto the hash of an accepted request.
     */
    @Test
    void theRequestHashDistinguishesARepeatedConfirmationFromTheAcceptedOne() {
        ContentHash hash = otherHash("preparation");
        PublishCommand accepted = new PublishCommand("preparation-1", hash, "key-1",
                List.of("HIGH_REACH", "SEGMENT_SAMPLE_EXPOSED"));
        PublishCommand repeated = new PublishCommand("preparation-1", hash, "key-1",
                List.of("HIGH_REACH", "HIGH_REACH", "SEGMENT_SAMPLE_EXPOSED"));

        assertThat(repeated.requestHash())
                .as("a duplicate must not normalize onto the accepted request's hash")
                .isNotEqualTo(accepted.requestHash());
    }

    @Test
    void aPreparationRefusesAnOrdinaryConfirmationSet() {
        PublishPreparation preparation = preparation(Duration.ofMinutes(15));

        assertThat(preparation.requiresExactly(java.util.Set.of())).isTrue();
        assertThat(preparation.requiresExactly(java.util.Set.of("SOME_RISK"))).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private static PublishPreparation preparation(Duration lifetime) {
        DraftIdentity draft = new DraftIdentity("draft-000000000001", 1L);
        return PublishPreparation.of(
                "publish-preparation-000000000001",
                "tenant-001", "program-001", "user-001", "scope-008",
                "campaign-000000000001", 1L, draft.draftId(), draft.revision(), null,
                "plan-1", otherHash("plan"), otherHash("manifest"), otherHash("draft"),
                "catalog-31", otherHash("catalog"), "policy-8", otherHash("policy"),
                List.of(new CapabilityPin("segment.rule-based", "1.0")),
                List.of(), List.of(), "segment.rule-based", "ACTIVE",
                otherHash("gate-inputs"), otherHash("approved-decision"),
                CREATED_AT, CREATED_AT.plus(lifetime));
    }

    private static ContentHash otherHash(String seed) {
        return ContentHash.sha256(seed.getBytes(StandardCharsets.UTF_8));
    }
}