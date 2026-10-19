package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.InMemoryCampaignPlanStore;
import com.company.campaign.lifecycle.testsupport.DelegatingLifecycleStore;
import com.company.campaign.lifecycle.testsupport.LifecycleTestFixtures;
import com.company.campaign.lifecycle.testsupport.PublishTestFixtures;
import com.company.campaign.lifecycle.testsupport.SwitchableAuditSink;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * T17 requirement 10: an injected authorization, validation, audit or transactional-store failure
 * demonstrates atomic rollback. Preparing, publishing and forking each commit their material and their audit
 * event as one unit, so a failing collaborator leaves no partial state — and never consumption of a
 * preparation that produced nothing.
 */
class PublishTransactionalRollbackTest {

    @Test
    void anAuditFailureOnAnAcceptedPublishFreezesNothingAndLeavesThePreparationUnconsumed() {
        Fixture fixture = new Fixture();
        PreparePublishResult preparation = fixture.prepare();
        int eventsBefore = fixture.sink.events().size();

        fixture.sink.fail();
        assertThatThrownBy(() -> fixture.publish(preparation, "key-1"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(fixture.store.publishedCount()).isZero();
        assertThat(fixture.store.publishReceiptCount()).isZero();
        assertThat(fixture.store.isPreparationConsumed(preparation.preparationId())).isFalse();
        assertThat(fixture.sink.events()).hasSize(eventsBefore);

        // The preparation is still usable once the audit collaborator recovers, which proves nothing committed.
        fixture.sink.recover();
        assertThat(fixture.publish(preparation, "key-1").publishedRevision()).isEqualTo(1L);
    }

    @Test
    void anAuditFailureOnAnAcceptedPreparationLeavesNoPreparation() {
        Fixture fixture = new Fixture();
        fixture.sink.fail();

        assertThatThrownBy(fixture::prepare).isInstanceOf(IllegalStateException.class);

        assertThat(fixture.store.preparationCount()).isZero();
    }

    @Test
    void aFailingPublishStoreLeavesNoDefinitionNoReceiptAndNoConsumption() {
        Fixture fixture = new Fixture();
        PreparePublishResult preparation = fixture.prepare();
        LifecycleStore failing = new DelegatingLifecycleStore(fixture.store) {
            @Override
            public PublishReceipt commitPublish(PublishCommit commit) {
                throw new LifecycleException(
                        LifecycleErrorCodes.LIFECYCLE_INTERNAL_ERROR,
                        "storeUnavailable",
                        "The lifecycle store could not commit the publication.");
            }
        };
        PublishService service = fixture.publishService(failing);

        assertThatThrownBy(() -> service.confirm(
                fixture.command(preparation, "key-1"), fixture.context))
                .isInstanceOfSatisfying(LifecycleException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_INTERNAL_ERROR));

        assertThat(fixture.store.publishedCount()).isZero();
        assertThat(fixture.store.publishReceiptCount()).isZero();
        assertThat(fixture.store.isPreparationConsumed(preparation.preparationId())).isFalse();
    }

    @Test
    void aFailingPreparationCommitLeavesNoPreparation() {
        Fixture fixture = new Fixture();
        LifecycleStore failing = new DelegatingLifecycleStore(fixture.store) {
            @Override
            public PublishPreparation commitPreparation(PublishPreparationCommit commit) {
                throw new LifecycleException(
                        LifecycleErrorCodes.LIFECYCLE_INTERNAL_ERROR,
                        "storeUnavailable",
                        "The lifecycle store could not commit the preparation.");
            }
        };
        PublishPreparationService service = PublishTestFixtures.prepareService(
                fixture.plans, failing, PublishTestFixtures.readiness(PublishTestFixtures.activeProjection()),
                LifecycleTestFixtures.CLOCK, fixture.ids);

        assertThatThrownBy(() -> service.prepare(fixture.prepareCommand(), fixture.context))
                .isInstanceOf(LifecycleException.class);

        assertThat(fixture.store.preparationCount()).isZero();
    }

    @Test
    void aFailingForkCommitLeavesTheEditableDraftUntouched() {
        Fixture fixture = new Fixture();
        PreparePublishResult preparation = fixture.prepare();
        PublishResult published = fixture.publish(preparation, "key-1");
        SavedCampaignDraft before = fixture.store.findDraft(fixture.campaignId()).orElseThrow();

        LifecycleStore failing = new DelegatingLifecycleStore(fixture.store) {
            @Override
            public SavedCampaignDraft commitFork(ForkCommit commit) {
                throw new LifecycleException(
                        LifecycleErrorCodes.LIFECYCLE_INTERNAL_ERROR,
                        "storeUnavailable",
                        "The lifecycle store could not commit the fork.");
            }
        };
        PublishedForkService service = new PublishedForkService(
                PublishTestFixtures.authorization(), failing, fixture.ids, LifecycleTestFixtures.CLOCK);

        assertThatThrownBy(() -> service.fork(
                new ForkPublishedCommand(published.publishedId(), published.publishedRevision()),
                fixture.context))
                .isInstanceOf(LifecycleException.class);

        SavedCampaignDraft after = fixture.store.findDraft(fixture.campaignId()).orElseThrow();
        assertThat(after).isSameAs(before);
        assertThat(after.draftId()).isEqualTo(before.draftId());
        assertThat(after.draftRevision()).isEqualTo(before.draftRevision());
        assertThat(fixture.store.publishedCount()).isEqualTo(1);
    }

    /**
     * R1 requirement 2: the bounded rejection stays primary when its own audit also fails, and the failing
     * sink is attached as suppressed evidence rather than replacing the stable reason outward.
     */
    @Test
    void aRejectionWhoseAuditCannotBeRecordedStillFailsClosedWithItsBoundedReason() {
        Fixture fixture = new Fixture();
        PreparePublishResult preparation = fixture.prepare();
        fixture.sink.fail();

        // The command is rejected for a mismatched preparation hash, and the rejection audit fails too.
        PublishCommand forged = new PublishCommand(
                preparation.preparationId(),
                com.company.campaign.shared.canonical.ContentHash.sha256(
                        "forged".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "key-1", List.of());

        Throwable failure = catchThrowableOfType(RuntimeException.class,
                () -> fixture.publishService(fixture.store).confirm(forged, fixture.context));

        assertThat(failure).isInstanceOfSatisfying(LifecycleException.class, bounded -> assertThat(bounded.code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_PREPARATION_MISMATCH));
        assertThat(failure.getSuppressed())
                .hasSize(1)
                .allSatisfy(suppressed -> assertThat(suppressed).isInstanceOf(IllegalStateException.class));

        assertThat(fixture.store.publishedCount()).isZero();
        assertThat(fixture.store.isPreparationConsumed(preparation.preparationId())).isFalse();
    }

    /** One deterministic publish chain over an audit sink a test can switch off and on. */
    private static final class Fixture {

        final SwitchableAuditSink sink = new SwitchableAuditSink();
        final InMemoryLifecycleStore store = new InMemoryLifecycleStore(sink);
        final SequentialLifecycleIdGenerator ids = new SequentialLifecycleIdGenerator();
        final CampaignPlan plan = PublishTestFixtures.activeSegmentPlan();
        final InMemoryCampaignPlanStore plans = PublishTestFixtures.planStore(plan);
        final com.company.campaign.security.context.TrustedExecutionContext context =
                PublishTestFixtures.context();
        final String campaignId;

        Fixture() {
            ApplyResult applied = PublishTestFixtures.applyService(plans, store, ids)
                    .apply(new ApplyCommand(plan.planId(), plan.planHash()), context);
            this.campaignId = applied.campaignId();
        }

        String campaignId() {
            return campaignId;
        }

        PreparePublishCommand prepareCommand() {
            SavedCampaignDraft current = store.findDraft(campaignId()).orElseThrow();
            return new PreparePublishCommand(
                    current.campaignId(), current.draftId(), current.draftRevision());
        }

        PreparePublishResult prepare() {
            return PublishTestFixtures.prepareService(
                    plans, store, PublishTestFixtures.readiness(PublishTestFixtures.activeProjection()),
                    LifecycleTestFixtures.CLOCK, ids)
                    .prepare(prepareCommand(), context);
        }

        PublishService publishService(LifecycleStore target) {
            return new PublishService(
                    plans, PublishTestFixtures.authorization(),
                    LifecycleTestFixtures.compatible(), LifecycleTestFixtures.valid(),
                    PublishTestFixtures.readiness(PublishTestFixtures.activeProjection()),
                    target, ids, LifecycleTestFixtures.CLOCK);
        }

        PublishCommand command(PreparePublishResult preparation, String key) {
            return new PublishCommand(
                    preparation.preparationId(), preparation.preparationHash(), key, List.of());
        }

        PublishResult publish(PreparePublishResult preparation, String key) {
            return publishService(store).confirm(command(preparation, key), context);
        }
    }
}