package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.evaluation.segment.contract.GateOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.company.campaign.evaluation.segment.gate.GateTestSupport.DECIDED_AT;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.hash;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.passedDecision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Section 4 and section 5: approval is host-only, requires the exact proposed-decision hash plus an authorized
 * accountable PRODUCT/DATA/TEST owner quorum, and is immutable and deterministic.
 */
class GateApprovalTest {

    private static final String PRODUCT_OWNER = "owner-product-1";
    private static final String DATA_OWNER = "owner-data-1";
    private static final String TEST_OWNER = "owner-test-1";

    // --- authorized quorum -----------------------------------------------------------------------

    @Test
    void anAuthorizedQuorumNamesTheExactDecisionHashAndIsDeterministic() {
        GateDecision decision = passedDecision();
        AtomicInteger calls = new AtomicInteger();
        GateApprovalService service = new GateApprovalService(allowAll(calls));

        GateApprovalRecord approval = service.approve(decision, GateTestSupport.quorumCommand(decision.decisionHash()));
        GateApprovalRecord again = service.approve(decision, GateTestSupport.quorumCommand(decision.decisionHash()));

        assertThat(approval.decisionHash()).isEqualTo(decision.decisionHash());
        assertThat(approval.outcome()).isEqualTo(GateOutcome.PASSED);
        assertThat(approval.ownerApprovals()).extracting(GateOwnerApproval::ownerRole)
                .containsExactly(GateOwnerRole.PRODUCT, GateOwnerRole.DATA, GateOwnerRole.TEST);
        assertThat(approval.ownerApprovals()).extracting(GateOwnerApproval::ownerRef)
                .containsExactly(PRODUCT_OWNER, DATA_OWNER, TEST_OWNER);
        assertThat(approval.hasCompleteQuorum()).isTrue();
        assertThat(approval.approvalHash()).isEqualTo(approval.recomputeHash());
        assertThat(approval.canonicalBytes()).isEqualTo(again.canonicalBytes());
        assertThat(approval.approvalHash()).isEqualTo(again.approvalHash());
        assertThat(calls).hasValue(6);
    }

    @Test
    void anApprovalRecordsTheDecisionVerdictItApproves() {
        GateDecision decision = passedDecision();
        GateApprovalRecord approval = new GateApprovalService(allowAll(new AtomicInteger()))
                .approve(decision, GateTestSupport.quorumCommand(decision.decisionHash()));

        assertThat(approval.outcome()).isEqualTo(decision.outcome());
    }

    @Test
    void ownerApprovalOrderNeverChangesTheApprovalBytes() {
        GateDecision decision = passedDecision();
        GateApprovalService service = new GateApprovalService(allowAll(new AtomicInteger()));
        GateOwnerApproval product = GateTestSupport.owner(PRODUCT_OWNER, GateOwnerRole.PRODUCT);
        GateOwnerApproval data = GateTestSupport.owner(DATA_OWNER, GateOwnerRole.DATA);
        GateOwnerApproval test = GateTestSupport.owner(TEST_OWNER, GateOwnerRole.TEST);

        GateApprovalRecord inOrder = service.approve(decision,
                new GateApprovalCommand(decision.decisionHash(), List.of(product, data, test)));
        GateApprovalRecord shuffled = service.approve(decision,
                new GateApprovalCommand(decision.decisionHash(), List.of(test, product, data)));

        assertThat(shuffled.canonicalBytes()).isEqualTo(inOrder.canonicalBytes());
        assertThat(shuffled.approvalHash()).isEqualTo(inOrder.approvalHash());
    }

    // --- forged and stale decisions --------------------------------------------------------------

    @Test
    void aForgedDecisionWithAMismatchedHashIsRejectedBeforeAuthorization() {
        GateDecision forged = passedDecision().withDecisionHash(hash("forged-decision"));
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> new GateApprovalService(allowAll(calls)).approve(
                forged, GateTestSupport.quorumCommand(forged.decisionHash())))
                .isInstanceOf(GateException.class)
                .extracting(exception -> ((GateException) exception).code())
                .isEqualTo(GateErrorCodes.APPROVAL_DECISION_HASH_MISMATCH);
        assertThat(calls).hasValue(0);
    }

    @Test
    void aStaleCommandNamingADifferentDecisionHashIsRejected() {
        GateDecision decision = passedDecision();
        GateApprovalCommand stale = GateTestSupport.quorumCommand(hash("some-other-decision"));

        assertThatThrownBy(() -> new GateApprovalService(allowAll(new AtomicInteger()))
                .approve(decision, stale))
                .isInstanceOf(GateException.class)
                .extracting(exception -> ((GateException) exception).code())
                .isEqualTo(GateErrorCodes.APPROVAL_DECISION_HASH_MISMATCH);
    }

    // --- incomplete quorum -----------------------------------------------------------------------

    @Test
    void oneOrTwoRolesNeverProduceAnApproval() {
        GateDecision decision = passedDecision();
        AtomicInteger calls = new AtomicInteger();
        GateApprovalService service = new GateApprovalService(allowAll(calls));

        for (List<GateOwnerApproval> partial : List.of(
                List.of(GateTestSupport.owner(PRODUCT_OWNER, GateOwnerRole.PRODUCT)),
                List.of(GateTestSupport.owner(PRODUCT_OWNER, GateOwnerRole.PRODUCT),
                        GateTestSupport.owner(DATA_OWNER, GateOwnerRole.DATA)))) {
            assertThatThrownBy(() -> service.approve(decision,
                    new GateApprovalCommand(decision.decisionHash(), partial)))
                    .isInstanceOf(GateException.class)
                    .extracting(exception -> ((GateException) exception).code())
                    .isEqualTo(GateErrorCodes.APPROVAL_QUORUM_INCOMPLETE);
        }
        assertThat(calls).as("no partial authorization is attempted").hasValue(0);
    }

    // --- unauthorized approvals ------------------------------------------------------------------

    @Test
    void aDeniedOwnerFailsTheWholeQuorumClosed() {
        GateDecision decision = passedDecision();
        AtomicInteger calls = new AtomicInteger();
        GateApprovalService service = new GateApprovalService((owner, role, hash) -> {
            calls.incrementAndGet();
            return role == GateOwnerRole.DATA
                    ? GateApprovalAuthorizationDecision.deny(owner, role, hash)
                    : GateApprovalAuthorizationDecision.allow(owner, role, hash);
        });

        assertThatThrownBy(() -> service.approve(decision, GateTestSupport.quorumCommand(decision.decisionHash())))
                .isInstanceOf(GateException.class)
                .extracting(exception -> ((GateException) exception).code())
                .isEqualTo(GateErrorCodes.APPROVAL_UNAUTHORIZED);
    }

    @Test
    void anAuthorizationEchoingADifferentRoleIsRejected() {
        GateDecision decision = passedDecision();
        GateApprovalService service = new GateApprovalService((owner, role, hash) ->
                GateApprovalAuthorizationDecision.allow(owner, GateOwnerRole.TEST, hash));

        assertThatThrownBy(() -> service.approve(decision, GateTestSupport.quorumCommand(decision.decisionHash())))
                .isInstanceOf(GateException.class)
                .extracting(exception -> ((GateException) exception).code())
                .isEqualTo(GateErrorCodes.APPROVAL_UNAUTHORIZED);
    }

    @Test
    void anAuthorizationEchoingADifferentOwnerIsRejected() {
        GateDecision decision = passedDecision();
        GateApprovalService service = new GateApprovalService((owner, role, hash) ->
                GateApprovalAuthorizationDecision.allow("owner-somebody-else", role, hash));

        assertThatThrownBy(() -> service.approve(decision, GateTestSupport.quorumCommand(decision.decisionHash())))
                .isInstanceOf(GateException.class)
                .extracting(exception -> ((GateException) exception).code())
                .isEqualTo(GateErrorCodes.APPROVAL_UNAUTHORIZED);
    }

    @Test
    void aThrowingAuthorizationFailsClosed() {
        GateDecision decision = passedDecision();
        GateApprovalService service = new GateApprovalService((owner, role, hash) -> {
            throw new IllegalStateException("authority unavailable");
        });

        assertThatThrownBy(() -> service.approve(decision, GateTestSupport.quorumCommand(decision.decisionHash())))
                .isInstanceOf(GateException.class)
                .extracting(exception -> ((GateException) exception).code())
                .isEqualTo(GateErrorCodes.APPROVAL_UNAUTHORIZED);
    }

    // --- immutable evidence ----------------------------------------------------------------------

    @Test
    void aTamperedApprovalHashIsRejected() {
        GateDecision decision = passedDecision();
        assertThatThrownBy(() -> new GateApprovalRecord(
                GateVersions.APPROVAL_SCHEMA_VERSION, decision.decisionHash(), GateOutcome.PASSED,
                GateTestSupport.quorum(), hash("tampered")))
                .isInstanceOf(GateException.class)
                .extracting(exception -> ((GateException) exception).code())
                .isEqualTo(GateErrorCodes.APPROVAL_INVALID);
    }

    @Test
    void anOwnerReferenceMustBeBoundedAndOpaque() {
        GateDecision decision = passedDecision();
        assertThatThrownBy(() -> new GateApprovalCommand(
                decision.decisionHash(), List.of(new GateOwnerApproval(
                        "owner@example.com", GateOwnerRole.PRODUCT, DECIDED_AT))))
                .isInstanceOf(GateException.class)
                .extracting(exception -> ((GateException) exception).code())
                .isEqualTo(GateErrorCodes.APPROVAL_INVALID);
        assertThatThrownBy(() -> new GateOwnerApproval(null, GateOwnerRole.PRODUCT, DECIDED_AT))
                .isInstanceOf(GateException.class);
        assertThat(new GateOwnerApproval(PRODUCT_OWNER, GateOwnerRole.PRODUCT, DECIDED_AT).approvedAt())
                .isEqualTo(DECIDED_AT);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static GateApprovalAuthorizationPort allowAll(AtomicInteger calls) {
        return (ownerRef, ownerRole, decisionHash) -> {
            calls.incrementAndGet();
            return GateApprovalAuthorizationDecision.allow(ownerRef, ownerRole, decisionHash);
        };
    }

    /** Kept so the test proves a wrong-hash echo is also a denial. */
    @Test
    void anAuthorizationEchoingADifferentDecisionHashIsRejected() {
        GateDecision decision = passedDecision();
        GateApprovalService service = new GateApprovalService((owner, role, hash) ->
                GateApprovalAuthorizationDecision.allow(owner, role, hash("other-decision")));

        assertThatThrownBy(() -> service.approve(decision, GateTestSupport.quorumCommand(decision.decisionHash())))
                .isInstanceOf(GateException.class)
                .extracting(exception -> ((GateException) exception).code())
                .isEqualTo(GateErrorCodes.APPROVAL_UNAUTHORIZED);
    }
}