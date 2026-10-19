package com.company.campaign.lifecycle;

import com.company.campaign.lifecycle.testsupport.DelegatingLifecycleStore;
import com.company.campaign.lifecycle.testsupport.ReferencePublishTestFixtures.ReferenceCase;
import com.company.campaign.lifecycle.testsupport.SwitchableAuditSink;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T18 section 7 at the T17 Publish transaction: a reference-bearing publication acquires every retention hold
 * it depends on atomically with the definition, its receipt, the consumption of the preparation and the audit
 * event — or leaves no hold at all.
 *
 * <p>The holds are the one durable consequence outside the lifecycle store, so the transaction that writes the
 * definition must acquire them in the same step. A failing audit collaborator, a store that cannot commit or
 * an exact idempotent retry must each leave the retention state exactly as the publication left it: nothing
 * half-acquired, and never a second hold for a dependent that was only published once.
 */
class PublishReferenceRetentionTest {

    @Test
    void anExactIdempotentRetryReturnsTheOriginalReceiptAndDoesNotDuplicateHolds() {
        ReferenceCase reference = ReferenceCase.approved();
        PreparePublishResult prepared = reference.prepare();
        int holdsPerPin = 1;

        PublishResult first = reference.scenario.confirm(prepared, "key-1", prepared.requiredRiskCodes());
        PublishResult second = reference.scenario.confirm(prepared, "key-1", prepared.requiredRiskCodes());

        assertThat(second.publishedIdentity().publishedId())
                .isEqualTo(first.publishedIdentity().publishedId());
        assertThat(reference.scenario.store.publishedCount()).isEqualTo(1);
        assertThat(reference.scenario.store.publishReceiptCount()).isEqualTo(1);
        assertThat(reference.scenario.retention.activeHoldCount())
                .isEqualTo(reference.dependencies.versions().size() * holdsPerPin);
    }

    @Test
    void anAuditFailureOnAReferenceBearingPublishRollsBackEveryHoldAndFreezesNothing() {
        SwitchableAuditSink sink = new SwitchableAuditSink();
        ReferenceCase reference = ReferenceCase.approved(sink);
        PreparePublishResult prepared = reference.prepare();
        sink.fail();

        assertThatThrownBy(() -> reference.scenario.confirm(
                prepared, "key-1", prepared.requiredRiskCodes()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(reference.scenario.store.publishedCount()).isZero();
        assertThat(reference.scenario.store.publishReceiptCount()).isZero();
        assertThat(reference.scenario.store.isPreparationConsumed(prepared.preparationId())).isFalse();
        assertThat(reference.scenario.retention.activeHoldCount()).isZero();

        // The preparation is still usable once the audit collaborator recovers, which proves nothing committed.
        sink.recover();
        assertThat(reference.scenario.confirm(prepared, "key-1", prepared.requiredRiskCodes())
                .publishedIdentity().revision()).isEqualTo(1L);
        assertThat(reference.scenario.retention.activeHoldCount())
                .isEqualTo(reference.dependencies.versions().size());
    }

    @Test
    void aCommitThatItselfFailsLeavesNoHoldAndNoPublishedState() {
        ReferenceCase reference = ReferenceCase.approved();
        PreparePublishResult prepared = reference.prepare();
        LifecycleStore failing = new DelegatingLifecycleStore(reference.scenario.store) {
            @Override
            public PublishReceipt commitPublish(PublishCommit commit) {
                throw new LifecycleException(
                        LifecycleErrorCodes.LIFECYCLE_INTERNAL_ERROR,
                        "storeUnavailable",
                        "The lifecycle store could not commit the publication.");
            }
        };

        PublishService service = reference.scenario.publishServiceOver(failing);

        assertThatThrownBy(() -> service.confirm(
                reference.scenario.command(prepared, "key-1", prepared.requiredRiskCodes()),
                reference.scenario.context))
                .isInstanceOfSatisfying(LifecycleException.class, failure -> assertThat(failure.code())
                        .isEqualTo(LifecycleErrorCodes.LIFECYCLE_INTERNAL_ERROR));

        assertThat(reference.scenario.store.publishedCount()).isZero();
        assertThat(reference.scenario.store.isPreparationConsumed(prepared.preparationId())).isFalse();
        assertThat(reference.scenario.retention.activeHoldCount()).isZero();
    }

    @Test
    void everyHoldNamesTheExactPinAndTheOnePublicationThatHoldsIt() {
        ReferenceCase reference = ReferenceCase.approved();
        PreparePublishResult prepared = reference.prepare();
        PublishResult published = reference.scenario.confirm(
                prepared, "key-1", prepared.requiredRiskCodes());

        List<String> holdIds = reference.dependencies.versions().stream()
                .flatMap(pin -> reference.scenario.retention.activeHolds(pin).stream())
                .map(hold -> hold.holdId())
                .distinct()
                .toList();

        // One distinct hold per exact pin, all bearing the same single holding publication.
        assertThat(holdIds).hasSize(reference.dependencies.versions().size());
        for (var pin : reference.dependencies.versions()) {
            assertThat(reference.scenario.retention.activeHolds(pin))
                    .singleElement()
                    .satisfies(hold -> {
                        assertThat(hold.holderVersionId())
                                .isEqualTo(published.publishedIdentity().publishedId());
                        assertThat(hold.heldReference().pinKey()).isEqualTo(pin.pinKey());
                    });
        }
    }
}