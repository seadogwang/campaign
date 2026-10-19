package com.company.campaign.evaluation.segment.review;

import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory query and decision repository for tests and local use only.
 *
 * <p>It implements both host ports so a test can drive the whole boundary without a database. Each instance
 * is created directly with explicit fixtures; there is no production default bean, no persistence, no file
 * or JDBC access and no clock. The committed corpus under {@code evaluation/segment/corpus-v1} is never
 * touched — a caller seeds immutable snapshots and the repository mutates only its own maps.
 *
 * <p>Commits are synchronized, so the compare-and-set is genuinely atomic within one instance: a commit
 * whose expected review hash is not the stored one loses the race, leaves the ledger untouched and records
 * no audit event. Audit events are append-only, so re-deciding a role preserves every earlier event.
 */
public final class InMemoryReviewDecisionRepository
        implements ReviewCaseQueryPort, ReviewDecisionRepositoryPort {

    private final Map<CaseIdentity, ReviewCaseSnapshot> snapshots = new LinkedHashMap<>();
    private final Map<CaseIdentity, List<ReviewAuditEvent>> auditHistory = new LinkedHashMap<>();

    /** Publishes one immutable snapshot. Identical content is idempotent; a differing revision conflicts. */
    public synchronized void seed(ReviewCaseSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        CaseIdentity identity = identity(snapshot.key());
        ReviewCaseSnapshot existing = snapshots.get(identity);
        if (existing == null) {
            snapshots.put(identity, snapshot);
            return;
        }
        if (!existing.equals(snapshot)) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A different review snapshot is already stored for this case identity");
        }
    }

    /** Loads the snapshot only when the stored key is exactly the requested key. */
    @Override
    public synchronized ReviewCaseSnapshot loadSnapshot(ReviewCaseKey caseKey) {
        if (caseKey == null) {
            return null;
        }
        ReviewCaseSnapshot stored = snapshots.get(identity(caseKey));
        if (stored == null || !stored.key().equals(caseKey)) {
            return null;
        }
        return stored;
    }

    /** Atomically compare-and-sets, stores and audits one decision. */
    @Override
    public synchronized void commit(ReviewDecisionCommit commit) {
        Objects.requireNonNull(commit, "commit");
        CaseIdentity identity = identity(commit.caseKey());
        ReviewCaseSnapshot current = snapshots.get(identity);
        if (current == null || !current.key().equals(commit.caseKey())) {
            throw ReviewCaseQueryPort.notFound();
        }
        if (!current.review().reviewHash().equals(commit.expectedReviewHash())) {
            throw ReviewDecisionRepositoryPort.conflict();
        }
        if (!commit.updatedReview().caseHash().equals(current.review().caseHash())
                || !commit.updatedReview().expectedHash().equals(current.review().expectedHash())) {
            // A commit that would repoint the ledger at other bytes than the stored revision is invalid.
            throw ReviewDecisionRepositoryPort.conflict();
        }
        snapshots.put(identity, new ReviewCaseSnapshot(
                current.key(), current.caseInput(), current.expectedResult(), commit.updatedReview()));
        auditHistory.computeIfAbsent(identity, key -> new ArrayList<>()).add(commit.auditEvent());
    }

    /** The immutable audit history for one case, in decision order. */
    public synchronized List<ReviewAuditEvent> auditEvents(String corpusVersion, String caseId) {
        List<ReviewAuditEvent> events = auditHistory.get(new CaseIdentity(corpusVersion, caseId));
        return events == null ? List.of() : List.copyOf(events);
    }

    /** How many case revisions are currently stored. */
    public synchronized int size() {
        return snapshots.size();
    }

    private static CaseIdentity identity(ReviewCaseKey key) {
        return new CaseIdentity(key.corpusVersion(), key.caseId());
    }

    private record CaseIdentity(String corpusVersion, String caseId) {
    }
}