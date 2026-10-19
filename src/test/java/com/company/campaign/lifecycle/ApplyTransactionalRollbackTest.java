package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.lifecycle.testsupport.DelegatingLifecycleStore;
import com.company.campaign.lifecycle.testsupport.LifecycleTestFixtures;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static com.company.campaign.lifecycle.testsupport.LifecycleTestFixtures.createPlan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * T17 requirement 10: an injected audit or transactional-store failure demonstrates atomic rollback — the
 * Draft, its receipt and its audit event are committed as one unit, so a failing collaborator leaves no
 * partial state.
 */
class ApplyTransactionalRollbackTest {

    private static final ContentHash OTHER_HASH =
            ContentHash.sha256("some-other-plan".getBytes(StandardCharsets.UTF_8));

    @Test
    void anAuditFailureOnAnAcceptedApplyLeavesNoDraftAndNoReceipt() {
        CampaignPlan plan = createPlan();
        InMemoryLifecycleStore store = new InMemoryLifecycleStore(failingAudit());
        ApplyService service = LifecycleTestFixtures.service(LifecycleTestFixtures.planStore(plan), store);

        assertThatThrownBy(() -> service.apply(command(plan), LifecycleTestFixtures.context()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(store.draftCount()).isZero();
        assertThat(store.receiptCount()).isZero();
    }

    @Test
    void aFailingTransactionalStoreLeavesNoPartialState() {
        CampaignPlan plan = createPlan();
        InMemoryLifecycleStore delegate = LifecycleTestFixtures.store();
        ApplyService service = LifecycleTestFixtures.service(
                LifecycleTestFixtures.planStore(plan), new FailingCommitStore(delegate));

        assertThatThrownBy(() -> service.apply(command(plan), LifecycleTestFixtures.context()))
                .isInstanceOf(LifecycleException.class)
                .satisfies(failure -> assertThat(((LifecycleException) failure).code())
                        .isEqualTo(LifecycleErrorCodes.LIFECYCLE_INTERNAL_ERROR));

        assertThat(delegate.draftCount()).isZero();
        assertThat(delegate.receiptCount()).isZero();
    }

    /**
     * R1 requirement 2: the bounded lifecycle reason stays primary when its own rejection audit also fails.
     *
     * <p>Recording the rejection is best-effort evidence, never a substitute for the reason: an unrestricted
     * sink exception may not replace the stable code the caller was refused with. The sink failure is
     * attached as suppressed so it is still observable.
     */
    @Test
    void aRejectionWhoseAuditCannotBeRecordedStillFailsClosedWithItsBoundedReason() {
        CampaignPlan plan = createPlan();
        InMemoryLifecycleStore store = new InMemoryLifecycleStore(failingAudit());
        ApplyService service = LifecycleTestFixtures.service(LifecycleTestFixtures.planStore(plan), store);

        // The command is rejected for a wrong hash, and the rejection audit cannot be recorded either.
        Throwable failure = catchThrowableOfType(RuntimeException.class,
                () -> service.apply(new ApplyCommand(plan.planId(), OTHER_HASH),
                        LifecycleTestFixtures.context()));

        assertThat(failure).isInstanceOfSatisfying(LifecycleException.class,
                bounded -> assertThat(bounded.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_PLAN_MISMATCH));
        assertThat(failure.getSuppressed())
                .as("the failing sink stays observable as evidence attached to the bounded reason")
                .hasSize(1)
                .allSatisfy(suppressed -> assertThat(suppressed).isInstanceOf(IllegalStateException.class));

        assertThat(store.draftCount()).isZero();
        assertThat(store.receiptCount()).isZero();
    }

    private static LifecycleAuditSink failingAudit() {
        return event -> {
            throw new IllegalStateException("audit sink unavailable");
        };
    }

    private static ApplyCommand command(CampaignPlan plan) {
        return new ApplyCommand(plan.planId(), plan.planHash());
    }

    /** A store whose reads and rejection audit work but whose one transactional write always fails. */
    private static final class FailingCommitStore extends DelegatingLifecycleStore {

        private FailingCommitStore(InMemoryLifecycleStore delegate) {
            super(delegate);
        }

        @Override
        public ApplyReceipt commitApply(ApplyCommit commit) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_INTERNAL_ERROR,
                    "storeUnavailable",
                    "The lifecycle store could not commit the Apply.");
        }
    }
}