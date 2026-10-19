package com.company.campaign.lifecycle;

import com.company.campaign.lifecycle.testsupport.PublishScenario;
import com.company.campaign.lifecycle.testsupport.ReferencePublishTestFixtures;
import com.company.campaign.lifecycle.testsupport.ReferencePublishTestFixtures.ReferenceCase;
import com.company.campaign.segment.reference.AudienceRetentionHold;
import com.company.campaign.segment.reference.AudienceVersionRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T18 section 7 at the T17 Publish boundary: the dedicated Existing Audience Reference recheck and the atomic
 * retention holds one reference-bearing publication produces.
 *
 * <p>Publishing is not the authority preparing was. A reference-bearing preparation freezes the exact
 * dedicated {@code segment.existing-audience-reference} activation and the exact transitive pins it depends
 * on, and this boundary re-proves both against trusted host state immediately before anything is frozen: the
 * host must re-resolve the very dependency closure the preparation froze (so a version, a hash, an
 * availability, a program relation, a scope or a retention answer that moved is drift), and the latest
 * approved reference decision must still be the exact one the preparation froze over the exact reference
 * revision it froze. Everything weaker fails closed and freezes nothing and holds nothing.
 *
 * <p>A reference-free publication acquires no retention hold and keeps the accepted T17 behavior: the
 * reference slot is optional and the reference ports are host-only additions, so the accepted path never
 * consults them.
 */
class PublishReferenceTest {

    // ------------------------------------------------------------------ the exact recheck

    @Test
    void aReferenceBearingPublishReProvesTheFrozenActivationAndAcquiresOneHoldPerExactPin() {
        ReferenceCase reference = ReferenceCase.approved();
        PreparePublishResult prepared = reference.prepare();

        PublishResult published = reference.scenario.confirm(
                prepared, "key-1", prepared.requiredRiskCodes());

        // The publication is exactly one immutable definition, and the preparation was consumed by it.
        assertThat(published.publishedIdentity().revision()).isEqualTo(1L);
        assertThat(reference.scenario.store.publishedCount()).isEqualTo(1);
        assertThat(reference.scenario.store.isPreparationConsumed(prepared.preparationId())).isTrue();

        // And exactly one deterministic retention hold per exact re-resolved pin, keyed by the publication.
        assertThat(reference.dependencies.versions()).isNotEmpty();
        for (AudienceVersionRef pin : reference.dependencies.versions()) {
            List<AudienceRetentionHold> holds = reference.scenario.retention.activeHolds(pin);
            assertThat(holds).hasSize(1);
            AudienceRetentionHold hold = holds.get(0);
            assertThat(hold.heldReference()).isEqualTo(pin);
            assertThat(hold.holderAudienceId()).isEqualTo(reference.scenario.campaignId());
            assertThat(hold.holderVersionId()).isEqualTo(published.publishedIdentity().publishedId());
            assertThat(hold.acquiredAt()).isEqualTo(published.publishedAt());
        }
        assertThat(reference.scenario.retention.activeHoldCount())
                .isEqualTo(reference.dependencies.versions().size());
    }

    // ------------------------------------------------------------------ the recheck fails closed

    @Test
    void aMissingOrSupersededOrStaleReferenceApprovalFailsClosedAtPublish() {
        // Three genuinely different re-authorization failures, each applied *after* a successful Prepare: the
        // host holds no approval at all, the host's latest approved decision is a different one over the same
        // inputs, and the host's current reference revision is no longer the one the preparation froze.
        List<Prepared> drifted = List.of(
                approvedThen(reference -> reference.withApprovalPort(
                        ReferencePublishTestFixtures.missingApproval())),
                approvedThen(reference -> reference.withApprovalPort(ReferencePublishTestFixtures.approval(
                        ReferencePublishTestFixtures.supersedingApproval(ReferencePublishTestFixtures.inputs())))),
                approvedThen(reference -> reference.withCurrentReferenceInputs(
                        ReferencePublishTestFixtures.otherInputs())));

        for (Prepared prepared : drifted) {
            assertThatThrownBy(() -> prepared.confirm())
                    .isInstanceOfSatisfying(LifecycleException.class, failure -> assertThat(failure.code())
                            .isEqualTo(LifecycleErrorCodes.LIFECYCLE_REFERENCE_ACTIVATION_REJECTED));

            assertThat(prepared.reference.scenario.store.publishedCount()).isZero();
            assertThat(prepared.reference.scenario.store
                    .isPreparationConsumed(prepared.result.preparationId())).isFalse();
            assertThat(prepared.reference.scenario.retention.activeHoldCount()).isZero();
        }
    }

    @Test
    void aReResolvedDependencyGraphOtherThanTheFrozenOneFailsClosedAtPublish() {
        Prepared prepared = approvedThen(reference -> reference.withFactsPort(
                ReferencePublishTestFixtures.facts(new ReferencePreparationFacts(
                        ReferencePublishTestFixtures.otherDependencies(),
                        ReferencePublishTestFixtures.inputs()))));

        assertThatThrownBy(prepared::confirm)
                .isInstanceOfSatisfying(LifecycleException.class, failure -> assertThat(failure.code())
                        .isEqualTo(LifecycleErrorCodes.LIFECYCLE_REFERENCE_ACTIVATION_REJECTED));

        assertThat(prepared.reference.scenario.store.publishedCount()).isZero();
        assertThat(prepared.reference.scenario.store
                .isPreparationConsumed(prepared.result.preparationId())).isFalse();
        assertThat(prepared.reference.scenario.retention.activeHoldCount()).isZero();
    }

    @Test
    void anUnanswerableOrAbsentReferenceHostFailsClosedAtPublish() {
        List<Prepared> refusing = List.of(
                approvedThen(reference -> reference.withFactsPort(ReferencePublishTestFixtures.missingFacts())),
                approvedThen(reference -> reference.withFactsPort(ReferencePublishTestFixtures.failingFacts())),
                approvedThen(reference -> reference.withApprovalPort(ReferencePublishTestFixtures.failingApproval())),
                approvedThen(ReferenceCase::withoutReferenceWiring));

        for (Prepared prepared : refusing) {
            assertThatThrownBy(prepared::confirm)
                    .isInstanceOfSatisfying(LifecycleException.class, failure -> assertThat(failure.code()).isIn(
                            LifecycleErrorCodes.LIFECYCLE_REFERENCE_ACTIVATION_REJECTED,
                            LifecycleErrorCodes.LIFECYCLE_REFERENCE_ACTIVATION_UNAVAILABLE));

            assertThat(prepared.reference.scenario.store.publishedCount()).isZero();
            assertThat(prepared.reference.scenario.retention.activeHoldCount()).isZero();
        }
    }

    @Test
    void theReferenceDriftRejectionIsAuditedOnceWithItsOwnBoundedCode() {
        Prepared prepared = approvedThen(reference -> reference.withFactsPort(
                ReferencePublishTestFixtures.facts(new ReferencePreparationFacts(
                        ReferencePublishTestFixtures.otherDependencies(),
                        ReferencePublishTestFixtures.inputs()))));
        int before = prepared.reference.scenario.audit.size();

        assertThatThrownBy(prepared::confirm).isInstanceOf(LifecycleException.class);

        assertThat(prepared.reference.scenario.audit.size()).isEqualTo(before + 1);
        LifecycleAuditEvent event = prepared.reference.scenario.audit.events().get(before);
        assertThat(event.action()).isEqualTo(LifecycleAction.PUBLISH);
        assertThat(event.disposition()).isEqualTo(LifecycleDisposition.REJECTED);
        assertThat(event.reasonCode())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_REFERENCE_ACTIVATION_REJECTED);
    }

    // ------------------------------------------------------------------ the reference-free regression

    @Test
    void aReferenceFreePublishIsUnchangedAndAcquiresNoRetentionHold() {
        PublishScenario plain = PublishScenario.active();
        PreparePublishResult prepared = plain.prepare();

        PublishResult published = plain.confirm(prepared, "key-1", prepared.requiredRiskCodes());

        assertThat(published.publishedIdentity().revision()).isEqualTo(1L);
        assertThat(plain.store.publishedCount()).isEqualTo(1);
        assertThat(plain.store.publishReceiptCount()).isEqualTo(1);
        assertThat(plain.store.isPreparationConsumed(prepared.preparationId())).isTrue();
        assertThat(plain.retention.activeHoldCount()).isZero();
    }

    @Test
    void aReferenceFreePlanNeverConsultsAReferencePortAtPublish() {
        // A host whose reference ports throw every time still publishes a reference-free plan: the reference
        // extension is not on a reference-free path at Publish either.
        PublishScenario plain = PublishScenario.active()
                .withReferencePorts(
                        ReferencePublishTestFixtures.failingFacts(),
                        ReferencePublishTestFixtures.failingApproval());
        PreparePublishResult prepared = plain.prepare();

        assertThat(plain.confirm(prepared, "key-1", prepared.requiredRiskCodes())
                .publishedIdentity().revision()).isEqualTo(1L);
        assertThat(plain.retention.activeHoldCount()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    /** One preparation made under the exact approved activation, with its host ports still mutable. */
    private record Prepared(ReferenceCase reference, PreparePublishResult result) {

        void confirm() {
            reference.scenario.confirm(result, "key-1", result.requiredRiskCodes());
        }
    }

    /**
     * Prepares a reference-bearing Draft under the exact approved activation, then applies one post-Prepare
     * drift. The preparation is therefore always genuine; only the host's later answer differs.
     */
    private static Prepared approvedThen(java.util.function.UnaryOperator<ReferenceCase> drift) {
        ReferenceCase approved = ReferenceCase.approved();
        PreparePublishResult prepared = approved.prepare();
        return new Prepared(drift.apply(approved), prepared);
    }
}