package com.company.campaign.evaluation.segment.review;

import com.company.campaign.evaluation.segment.contract.CaseReviewEntry;
import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.ReviewRole;
import com.company.campaign.evaluation.segment.contract.ReviewStatus;
import com.company.campaign.evaluation.segment.review.testsupport.RecordingReviewAuthorizationPort;
import com.company.campaign.evaluation.segment.review.testsupport.ReviewWorkflowFixtures;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.testsupport.ContractAssertions;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The host-only review command/query boundary: exact identity, exactly-once authorization, atomic CAS and
 * redacted immutable audit.
 */
class ReviewDecisionServiceTest {

    private static final ReviewActor ACTOR_A = new ReviewActor("reviewer-product-001");
    private static final ReviewActor ACTOR_B = new ReviewActor("reviewer-data-001");
    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private static CorpusBundle bundle;

    @BeforeAll
    static void loadOnce() {
        bundle = ReviewWorkflowFixtures.loadCorpusV1();
    }

    // --- the query side ---------------------------------------------------------------------------

    @Test
    void queryReturnsTheExactImmutableCaseExpectedAndReviewSnapshot() {
        Harness harness = harness(RecordingReviewAuthorizationPort.allowing());
        ReviewCaseKey key = ReviewWorkflowFixtures.keyFor(bundle, 0);

        ReviewCaseSnapshot snapshot = harness.repository().loadSnapshot(key);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.caseInput()).isEqualTo(bundle.cases().get(0));
        assertThat(snapshot.expectedResult()).isEqualTo(bundle.expectedResults().get(0));
        assertThat(snapshot.review()).isEqualTo(bundle.reviews().get(0));
        assertThat(snapshot.key()).isEqualTo(key);

        // A key with a stale case hash finds nothing rather than a neighbouring revision.
        ReviewCaseKey stale = new ReviewCaseKey(
                key.corpusVersion(), key.caseId(), hash("other-case"),
                key.expectedHash());
        assertThat(harness.repository().loadSnapshot(stale)).isNull();
    }

    // --- one authorized decision ------------------------------------------------------------------

    @Test
    void oneAuthorizedDecisionChangesOnlyOneRoleAndReturnsARedactedReceipt() {
        Harness harness = harness(RecordingReviewAuthorizationPort.allowing());
        ReviewCaseKey key = ReviewWorkflowFixtures.keyFor(bundle, 0);
        CaseReviewEvidence before = harness.repository().loadSnapshot(key).review();

        ReviewDecisionReceipt receipt = harness.service().decide(ACTOR_A, command(
                key, before.reviewHash(), ReviewRole.PRODUCT, ReviewStatus.APPROVED,
                "evidence-product-001", "Approved product for this exact revision."));

        assertThat(receipt.role()).isEqualTo(ReviewRole.PRODUCT);
        assertThat(receipt.status()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(receipt.reviewHash()).isNotEqualTo(before.reviewHash());
        assertThat(receipt.auditEventHash()).isNotNull();
        assertThat(harness.authorization().callCount()).isEqualTo(1);
        assertThat(harness.authorization().requestedRoles()).containsExactly(ReviewRole.PRODUCT);

        ReviewCaseSnapshot after = harness.repository().loadSnapshot(key);
        assertThat(after.review().entryFor(ReviewRole.PRODUCT).status()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(after.review().entryFor(ReviewRole.PRODUCT).reviewerRef())
                .isEqualTo(ACTOR_A.reviewerRef());
        assertThat(after.review().entryFor(ReviewRole.DATA).status()).isEqualTo(ReviewStatus.PENDING);
        assertThat(after.review().entryFor(ReviewRole.TEST).status()).isEqualTo(ReviewStatus.PENDING);
        assertThat(after.review().reviewHash()).isEqualTo(receipt.reviewHash());
        assertThat(after.caseInput()).isEqualTo(bundle.cases().get(0));
        assertThat(after.expectedResult()).isEqualTo(bundle.expectedResults().get(0));

        List<ReviewAuditEvent> events = harness.repository()
                .auditEvents(key.corpusVersion(), key.caseId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).auditEventHash()).isEqualTo(receipt.auditEventHash());
        assertThat(events.get(0).previousReviewHash()).isEqualTo(before.reviewHash());
        assertThat(events.get(0).newReviewHash()).isEqualTo(receipt.reviewHash());
    }

    // --- fail-closed authorization ----------------------------------------------------------------

    @Test
    void authorizationIsCalledExactlyOncePerDecision() {
        Harness harness = harness(RecordingReviewAuthorizationPort.allowing());
        ReviewCaseKey key = ReviewWorkflowFixtures.keyFor(bundle, 0);

        harness.service().decide(ACTOR_A, command(
                key, pendingHash(harness, key), ReviewRole.PRODUCT, ReviewStatus.APPROVED,
                "evidence-product-001", "Approved product."));

        assertThat(harness.authorization().callCount()).isEqualTo(1);
        assertThat(harness.authorization().requestedKeys()).containsExactly(key);
        assertThat(harness.authorization().requestedActors()).containsExactly(ACTOR_A);
    }

    @Test
    void everyNonExactAuthorizationFailsClosedWithoutWriteOrAudit() {
        ReviewCaseKey key = ReviewWorkflowFixtures.keyFor(bundle, 0);
        ReviewCaseKey otherKey = ReviewWorkflowFixtures.keyFor(bundle, 1);

        List<RecordingReviewAuthorizationPort> boundaries = List.of(
                RecordingReviewAuthorizationPort.returningNull(),
                RecordingReviewAuthorizationPort.denying(),
                RecordingReviewAuthorizationPort.allowingWrongRole(ReviewRole.DATA),
                RecordingReviewAuthorizationPort.allowingWrongCase(otherKey),
                RecordingReviewAuthorizationPort.throwing());

        for (RecordingReviewAuthorizationPort boundary : boundaries) {
            Harness harness = harness(boundary);
            ContentHash before = harness.repository().loadSnapshot(key).review().reviewHash();

            ContractAssertions.assertFailsWithCode(
                    () -> harness.service().decide(ACTOR_A, command(
                            key, before, ReviewRole.PRODUCT, ReviewStatus.APPROVED,
                            "evidence-product-001", "Approved product.")),
                    EvaluationErrorCodes.REVIEW_AUTHORIZATION_DENIED);

            assertThat(harness.repository().auditEvents(key.corpusVersion(), key.caseId())).isEmpty();
            assertThat(harness.repository().loadSnapshot(key).review().reviewHash()).isEqualTo(before);
            assertThat(boundary.callCount()).isEqualTo(1);
        }
    }

    // --- optimistic concurrency -------------------------------------------------------------------

    @Test
    void staleReviewCaseAndExpectedHashesLoseTheCasRaceAndPreserveTheLedger() {
        ReviewCaseKey key = ReviewWorkflowFixtures.keyFor(bundle, 0);
        Harness harness = harness(RecordingReviewAuthorizationPort.allowing());
        ContentHash before = harness.repository().loadSnapshot(key).review().reviewHash();

        // Stale review hash: the reviewer decided against a ledger that is no longer current.
        ContractAssertions.assertFailsWithCode(
                () -> harness.service().decide(ACTOR_A, command(
                        key, hash("stale-review"), ReviewRole.PRODUCT, ReviewStatus.APPROVED,
                        "evidence-product-001", "Approved product.")),
                EvaluationErrorCodes.REVIEW_CAS_CONFLICT);
        assertThat(harness.repository().loadSnapshot(key).review().reviewHash()).isEqualTo(before);
        assertThat(harness.repository().auditEvents(key.corpusVersion(), key.caseId())).isEmpty();

        // Stale case hash and stale expected hash name no exact revision, so the query finds nothing.
        for (ReviewCaseKey stale : List.of(
                new ReviewCaseKey(key.corpusVersion(), key.caseId(), hash("stale-case"), key.expectedHash()),
                new ReviewCaseKey(key.corpusVersion(), key.caseId(), key.caseHash(), hash("stale-expected")))) {
            ContractAssertions.assertFailsWithCode(
                    () -> harness.service().decide(ACTOR_A, command(
                            stale, before, ReviewRole.PRODUCT, ReviewStatus.APPROVED,
                            "evidence-product-001", "Approved product.")),
                    EvaluationErrorCodes.REVIEW_SNAPSHOT_NOT_FOUND);
        }
        assertThat(harness.repository().loadSnapshot(key).review().reviewHash()).isEqualTo(before);

        // The atomic port itself refuses a stale compare-and-set and keeps the current ledger.
        ReviewDecisionReceipt first = harness.service().decide(ACTOR_A, command(
                key, before, ReviewRole.PRODUCT, ReviewStatus.APPROVED,
                "evidence-product-001", "Approved product."));
        CaseReviewEvidence current = harness.repository().loadSnapshot(key).review();
        ReviewAuditEvent event = harness.repository()
                .auditEvents(key.corpusVersion(), key.caseId()).get(0);
        ReviewDecisionCommit staleCommit = new ReviewDecisionCommit(key, before, current, event);
        ContractAssertions.assertFailsWithCode(
                () -> harness.repository().commit(staleCommit), EvaluationErrorCodes.REVIEW_CAS_CONFLICT);
        assertThat(harness.repository().loadSnapshot(key).review()).isEqualTo(current);
        assertThat(first.reviewHash()).isEqualTo(current.reviewHash());
    }

    @Test
    void repeatingAnIdenticalCommandAfterSuccessIsAConflictNotASilentOverwrite() {
        Harness harness = harness(RecordingReviewAuthorizationPort.allowing());
        ReviewCaseKey key = ReviewWorkflowFixtures.keyFor(bundle, 0);
        ContentHash before = pendingHash(harness, key);
        ReviewDecisionCommand command = command(
                key, before, ReviewRole.PRODUCT, ReviewStatus.APPROVED, "evidence-product-001", "Approved.");

        ReviewDecisionReceipt first = harness.service().decide(ACTOR_A, command);
        ContractAssertions.assertFailsWithCode(
                () -> harness.service().decide(ACTOR_A, command), EvaluationErrorCodes.REVIEW_CAS_CONFLICT);

        assertThat(harness.repository().auditEvents(key.corpusVersion(), key.caseId())).hasSize(1);
        assertThat(harness.repository().loadSnapshot(key).review().reviewHash())
                .isEqualTo(first.reviewHash());
    }

    // --- independent reviewers --------------------------------------------------------------------

    @Test
    void oneReviewerCannotDecideTwoRolesOnTheSameCase() {
        Harness harness = harness(RecordingReviewAuthorizationPort.allowing());
        ReviewCaseKey key = ReviewWorkflowFixtures.keyFor(bundle, 0);
        ContentHash before = pendingHash(harness, key);

        ReviewDecisionReceipt first = harness.service().decide(ACTOR_A, command(
                key, before, ReviewRole.PRODUCT, ReviewStatus.APPROVED,
                "evidence-product-001", "Approved product."));

        ContractAssertions.assertFailsWithCode(
                () -> harness.service().decide(ACTOR_A, command(
                        key, first.reviewHash(), ReviewRole.DATA, ReviewStatus.APPROVED,
                        "evidence-data-001", "Approved data.")),
                EvaluationErrorCodes.REVIEW_REVIEWER_REUSED);

        CaseReviewEvidence blocked = harness.repository().loadSnapshot(key).review();
        assertThat(blocked.entryFor(ReviewRole.PRODUCT).status()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(blocked.entryFor(ReviewRole.DATA).status()).isEqualTo(ReviewStatus.PENDING);
        assertThat(harness.repository().auditEvents(key.corpusVersion(), key.caseId())).hasSize(1);

        // A distinct reviewer may still decide the other role.
        harness.service().decide(ACTOR_B, command(
                key, first.reviewHash(), ReviewRole.DATA, ReviewStatus.APPROVED,
                "evidence-data-001", "Approved data."));
        assertThat(harness.repository().loadSnapshot(key).review()
                .entryFor(ReviewRole.DATA).reviewerRef()).isEqualTo(ACTOR_B.reviewerRef());
    }

    // --- audit history ----------------------------------------------------------------------------

    @Test
    void aLaterExplicitDecisionForTheSameRolePreservesBothAuditEvents() {
        Harness harness = harness(RecordingReviewAuthorizationPort.allowing());
        ReviewCaseKey key = ReviewWorkflowFixtures.keyFor(bundle, 0);
        ContentHash before = pendingHash(harness, key);

        ReviewDecisionReceipt first = harness.service().decide(ACTOR_A, command(
                key, before, ReviewRole.PRODUCT, ReviewStatus.APPROVED,
                "evidence-product-001", "First decision."));
        ReviewDecisionReceipt second = harness.service().decide(ACTOR_B, command(
                key, first.reviewHash(), ReviewRole.PRODUCT, ReviewStatus.REJECTED,
                "evidence-product-002", "Second decision."));

        List<ReviewAuditEvent> events = harness.repository()
                .auditEvents(key.corpusVersion(), key.caseId());
        assertThat(events).hasSize(2);
        assertThat(events).extracting(ReviewAuditEvent::status)
                .containsExactly(ReviewStatus.APPROVED, ReviewStatus.REJECTED);
        assertThat(events.get(0).reviewerRef()).isEqualTo(ACTOR_A.reviewerRef());
        assertThat(events.get(1).reviewerRef()).isEqualTo(ACTOR_B.reviewerRef());
        assertThat(events.get(1).previousReviewHash()).isEqualTo(first.reviewHash());
        assertThat(events.get(1).newReviewHash()).isEqualTo(second.reviewHash());
        events.forEach(ReviewAuditEvent::requireValidContentHash);

        assertThat(harness.repository().loadSnapshot(key).review()
                .entryFor(ReviewRole.PRODUCT).status()).isEqualTo(ReviewStatus.REJECTED);
    }

    // --- forged / unrepresentable input -----------------------------------------------------------

    @Test
    void pendingStatusAndForgedFieldsAreUnrepresentableOrStrictlyRejected() {
        ReviewCaseKey key = ReviewWorkflowFixtures.keyFor(bundle, 0);

        ContractAssertions.assertFailsWithCode(
                () -> new ReviewDecisionCommand(
                        key, hash("review"), ReviewRole.PRODUCT, ReviewStatus.PENDING,
                        "evidence-product-001", ReviewWorkflowFixtures.DECISION_TIME, "Pending is not a decision."),
                EvaluationErrorCodes.REVIEW_STATUS_INVALID);

        // An email-shaped actor reference is outside the bounded opaque grammar.
        ContractAssertions.assertFailsWithCode(
                () -> new ReviewActor("product.owner@example.com"),
                EvaluationErrorCodes.REVIEW_REFERENCE_INVALID);

        // Unknown fields carrying a reviewer, Gate outcome or Case payload fail strict binding.
        for (String forgedField : List.of("reviewerRef", "gateOutcome", "caseInput", "permissions")) {
            Map<String, Object> payload = commandPayload(key, hash("review"), forgedField);
            String json = CANONICAL_JSON.writeString(payload);
            ContractAssertions.assertFailsWithCode(
                    () -> CANONICAL_JSON.read(json, ReviewDecisionCommand.class),
                    ContractErrorCodes.CONTRACT_UNKNOWN_FIELD);
        }

        // The command and receipt cannot even express reviewer identity, payload or activation fields.
        assertThat(componentNames(ReviewDecisionCommand.class))
                .doesNotContain("reviewerRef", "tenantId", "programId", "userId", "dataScopeId",
                        "permissions", "caseInput", "expectedResult", "gateOutcome",
                        "modelMetadata", "activation");
        assertThat(componentNames(ReviewDecisionReceipt.class))
                .doesNotContain("caseInput", "expectedResult", "notes", "reviewerRef", "payload");

        // A snapshot cannot bind a Case/Expected value to a key with a different hash.
        ReviewCaseKey wrongHash = new ReviewCaseKey(
                key.corpusVersion(), key.caseId(), hash("forged-case"), key.expectedHash());
        ContractAssertions.assertFailsWithCode(
                () -> new ReviewCaseSnapshot(
                        wrongHash, bundle.cases().get(0), bundle.expectedResults().get(0),
                        bundle.reviews().get(0)),
                EvaluationErrorCodes.REVIEW_DECISION_INVALID);

        // A receipt can never report a pending status.
        ContractAssertions.assertFailsWithCode(
                () -> new ReviewDecisionReceipt(
                        key.corpusVersion(), key.caseId(), ReviewRole.PRODUCT, ReviewStatus.PENDING,
                        hash("review"), hash("audit")),
                EvaluationErrorCodes.REVIEW_STATUS_INVALID);
    }

    // --- audit redaction --------------------------------------------------------------------------

    @Test
    void auditMaterialCarriesNoUtteranceAstMemberIdsNotesOrTrustedContext() {
        Harness harness = harness(RecordingReviewAuthorizationPort.allowing());
        ReviewCaseKey key = ReviewWorkflowFixtures.keyFor(bundle, 0);
        String marker = "notes-marker-must-not-be-audited";
        String utterance = bundle.cases().get(0).utterance();

        harness.service().decide(ACTOR_A, command(
                key, pendingHash(harness, key), ReviewRole.PRODUCT, ReviewStatus.APPROVED,
                "evidence-product-001", "Approved product " + marker));

        ReviewAuditEvent event = harness.repository()
                .auditEvents(key.corpusVersion(), key.caseId()).get(0);
        event.requireValidContentHash();
        String json = CANONICAL_JSON.writeString(event);

        assertThat(json).doesNotContain(marker);
        assertThat(json).doesNotContain(utterance);
        List<String> memberIds = bundle.expectedResults().get(0).expectedMemberIdsByFixture().values()
                .stream().flatMap(set -> set.memberIds().stream()).toList();
        assertThat(memberIds).isNotEmpty();
        for (String memberId : memberIds) {
            assertThat(json).doesNotContain(memberId);
        }
        assertThat(componentNames(ReviewAuditEvent.class))
                .doesNotContain("utterance", "expectedCanonicalAst", "expectedMemberIdsByFixture",
                        "memberIds", "notes", "reviewNotes", "tenantId", "programId", "userId",
                        "dataScopeId", "caseInput", "expectedResult", "permissions");
        assertThat(fieldNames(CANONICAL_JSON.readTree(json)))
                .doesNotContain("utterance", "expectedCanonicalAst", "expectedMemberIdsByFixture",
                        "notes", "reviewNotes", "tenantId", "programId", "userId", "dataScopeId",
                        "caseInput", "expectedResult");
        // The audit event must still bind the reviewer and evidence references it is allowed to carry.
        assertThat(json).contains(ACTOR_A.reviewerRef()).contains("evidence-product-001");
    }

    // --- helpers ----------------------------------------------------------------------------------

    private Harness harness(RecordingReviewAuthorizationPort authorization) {
        InMemoryReviewDecisionRepository repository = new InMemoryReviewDecisionRepository();
        repository.seed(ReviewWorkflowFixtures.snapshotFor(bundle, 0));
        return new Harness(
                repository, authorization,
                new ReviewDecisionService(repository, authorization, repository));
    }

    private static ContentHash pendingHash(Harness harness, ReviewCaseKey key) {
        return harness.repository().loadSnapshot(key).review().reviewHash();
    }

    private static ReviewDecisionCommand command(
            ReviewCaseKey key, ContentHash expectedReviewHash, ReviewRole role, ReviewStatus status,
            String evidenceRef, String notes) {
        return new ReviewDecisionCommand(
                key, expectedReviewHash, role, status, evidenceRef,
                ReviewWorkflowFixtures.DECISION_TIME, notes);
    }

    private static Map<String, Object> commandPayload(
            ReviewCaseKey key, ContentHash expectedReviewHash, String forgedField) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseKey", key);
        payload.put("expectedReviewHash", expectedReviewHash);
        payload.put("role", ReviewRole.PRODUCT.name());
        payload.put("status", ReviewStatus.APPROVED.name());
        payload.put("evidenceRef", "evidence-product-001");
        payload.put("decisionTime", ReviewWorkflowFixtures.DECISION_TIME.toString());
        payload.put("notes", "Approved product.");
        payload.put(forgedField, "forged-value");
        return payload;
    }

    private static ContentHash hash(String seed) {
        return ContentHash.sha256(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static Set<String> componentNames(Class<?> type) {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (java.lang.reflect.RecordComponent component : type.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    private static Set<String> fieldNames(JsonNode root) {
        Set<String> names = new java.util.LinkedHashSet<>();
        collectFieldNames(root, names);
        return names;
    }

    private static void collectFieldNames(JsonNode node, Set<String> names) {
        if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> fields = node.fields(); fields.hasNext(); ) {
                Map.Entry<String, JsonNode> field = fields.next();
                names.add(field.getKey());
                collectFieldNames(field.getValue(), names);
            }
        } else if (node.isArray()) {
            node.forEach(element -> collectFieldNames(element, names));
        }
    }

    private record Harness(
            InMemoryReviewDecisionRepository repository,
            RecordingReviewAuthorizationPort authorization,
            ReviewDecisionService service) {
    }
}