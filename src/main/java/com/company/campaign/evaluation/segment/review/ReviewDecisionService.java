package com.company.campaign.evaluation.segment.review;

import com.company.campaign.evaluation.segment.contract.CaseReviewEntry;
import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.company.campaign.evaluation.segment.contract.ReviewRole;
import com.company.campaign.shared.canonical.CanonicalJson;

import java.util.ArrayList;
import java.util.List;

/**
 * The host application service for one human review decision.
 *
 * <p>Every dependency is constructor-injected and mandatory — there is no no-argument, permissive or default
 * constructor — so a service that cannot load the exact revision, authorize the trusted actor and commit
 * atomically cannot exist. The service holds no mutable state, reads no clock, generates no identifier, and
 * touches no model, provider, network, database or runtime. The decision time and every reference come from
 * the command; the reviewer comes from the authenticated host.
 *
 * <p>For one call it performs the required order literally:
 * <ol>
 *   <li>load the exact immutable snapshot through the query port;</li>
 *   <li>re-prove the case, expected and review hashes and the key equality;</li>
 *   <li>authorize the trusted actor for the exact role and key exactly once;</li>
 *   <li>reject a reviewer reference already used on another role of the same case;</li>
 *   <li>replace only the requested role entry through the accepted canonical ledger construction;</li>
 *   <li>atomically compare-and-set the ledger and append the redacted immutable audit event;</li>
 *   <li>return only the redacted receipt.</li>
 * </ol>
 *
 * <p>Every failure is one stable, bounded, non-disclosing review code. A null, malformed, denied,
 * wrong-role, wrong-case or thrown authorization; a stale review/case/expected hash; a reused reviewer; or a
 * failed commit fails closed with no ledger write and no audit event.
 */
public final class ReviewDecisionService {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final ReviewCaseQueryPort queryPort;
    private final ReviewAuthorizationPort authorizationPort;
    private final ReviewDecisionRepositoryPort repositoryPort;

    public ReviewDecisionService(
            ReviewCaseQueryPort queryPort,
            ReviewAuthorizationPort authorizationPort,
            ReviewDecisionRepositoryPort repositoryPort) {
        this.queryPort = require(queryPort, "review query port");
        this.authorizationPort = require(authorizationPort, "review authorization port");
        this.repositoryPort = require(repositoryPort, "review repository port");
    }

    /** Submits one host-only human review decision and returns the redacted receipt. */
    public ReviewDecisionReceipt decide(ReviewActor actor, ReviewDecisionCommand command) {
        if (actor == null || command == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A review decision requires an authenticated actor and a complete command");
        }

        // 1. Load the one exact immutable snapshot the key names.
        ReviewCaseSnapshot snapshot = loadSnapshot(command.caseKey());
        ReviewCaseKey key = snapshot.key();

        // 2. Re-prove case/expected/review hashes and key equality, including the expected review hash.
        reprove(snapshot, command);

        // 3. Authorize the trusted actor for the exact role and key, exactly once, failing closed.
        authorizeOnce(actor, command.role(), key);

        // 4. One reviewer reference may decide only one role on this case.
        requireDistinctReviewer(snapshot.review(), command.role(), actor);

        // 5. Replace only the requested role entry through the accepted canonical construction.
        CaseReviewEvidence updated = replaceRole(snapshot.review(), command, actor);

        // 6. Hash-bind the redacted audit event and commit ledger plus audit as one atomic operation.
        ReviewAuditEvent auditEvent = ReviewAuditEvent.of(
                key.corpusVersion(), key.caseId(), key.caseHash(), key.expectedHash(),
                snapshot.review().reviewHash(), updated.reviewHash(),
                command.role(), command.status(), actor.reviewerRef(),
                command.evidenceRef(), command.decisionTime());
        commit(new ReviewDecisionCommit(key, command.expectedReviewHash(), updated, auditEvent));

        // 7. Return only the receipt: no Case/Expected payload, notes or reviewer identity.
        return new ReviewDecisionReceipt(
                key.corpusVersion(), key.caseId(), command.role(), command.status(),
                updated.reviewHash(), auditEvent.auditEventHash());
    }

    // ------------------------------------------------------------------ step 1: load

    private ReviewCaseSnapshot loadSnapshot(ReviewCaseKey key) {
        ReviewCaseSnapshot snapshot;
        try {
            snapshot = queryPort.loadSnapshot(key);
        } catch (EvaluationException exception) {
            throw ReviewCaseQueryPort.notFound();
        } catch (RuntimeException exception) {
            throw ReviewCaseQueryPort.notFound();
        }
        if (snapshot == null) {
            throw ReviewCaseQueryPort.notFound();
        }
        return snapshot;
    }

    // ------------------------------------------------------------------ step 2: re-prove

    private static void reprove(ReviewCaseSnapshot snapshot, ReviewDecisionCommand command) {
        ReviewCaseKey key = snapshot.key();
        if (!key.equals(command.caseKey())) {
            throw invalidDecision();
        }
        if (!CANONICAL_JSON.hash(snapshot.caseInput()).equals(key.caseHash())
                || !CANONICAL_JSON.hash(snapshot.expectedResult()).equals(key.expectedHash())) {
            throw invalidDecision();
        }
        CaseReviewEvidence review = snapshot.review();
        review.requireValidContentHash();
        if (!review.caseHash().equals(key.caseHash()) || !review.expectedHash().equals(key.expectedHash())) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_BINDING_STALE,
                    "The review ledger no longer binds the exact revision named by the case key");
        }
        if (!review.reviewHash().equals(command.expectedReviewHash())) {
            // The reviewer decided against a ledger that is no longer current: lose the race, write nothing.
            throw ReviewDecisionRepositoryPort.conflict();
        }
    }

    // ------------------------------------------------------------------ step 3: authorize

    private void authorizeOnce(ReviewActor actor, ReviewRole role, ReviewCaseKey key) {
        ReviewAuthorizationDecision decision;
        try {
            decision = authorizationPort.authorize(actor, role, key);
        } catch (RuntimeException exception) {
            throw authorizationDenied();
        }
        if (decision == null
                || !decision.allowed()
                || !actor.equals(decision.actor())
                || decision.role() != role
                || !key.equals(decision.caseKey())) {
            throw authorizationDenied();
        }
    }

    // ------------------------------------------------------------------ step 4: distinct reviewer

    private static void requireDistinctReviewer(CaseReviewEvidence review, ReviewRole role, ReviewActor actor) {
        for (CaseReviewEntry entry : review.reviews()) {
            if (entry.role() != role && entry.isDecided()
                    && actor.reviewerRef().equals(entry.reviewerRef())) {
                throw new EvaluationException(
                        EvaluationErrorCodes.REVIEW_REVIEWER_REUSED,
                        "A reviewer reference may decide only one role per case");
            }
        }
    }

    // ------------------------------------------------------------------ step 5: replace one role

    private static CaseReviewEvidence replaceRole(
            CaseReviewEvidence review, ReviewDecisionCommand command, ReviewActor actor) {
        List<CaseReviewEntry> entries = new ArrayList<>(review.reviews().size());
        for (CaseReviewEntry entry : review.reviews()) {
            if (entry.role() == command.role()) {
                entries.add(CaseReviewEntry.decided(
                        command.role(), command.status(), actor.reviewerRef(),
                        command.evidenceRef(), command.decisionTime(), command.notes()));
            } else {
                // Every other role entry is carried over byte-for-byte.
                entries.add(entry);
            }
        }
        return CaseReviewEvidence.of(
                review.schemaVersion(), review.caseId(), review.caseHash(), review.expectedHash(), entries);
    }

    // ------------------------------------------------------------------ step 6: commit

    private void commit(ReviewDecisionCommit commit) {
        try {
            repositoryPort.commit(commit);
        } catch (EvaluationException exception) {
            if (EvaluationErrorCodes.REVIEW_CAS_CONFLICT.equals(exception.code())
                    || EvaluationErrorCodes.REVIEW_SNAPSHOT_NOT_FOUND.equals(exception.code())) {
                throw exception;
            }
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_COMMIT_FAILED,
                    "The review repository failed to commit the decision");
        } catch (RuntimeException exception) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_COMMIT_FAILED,
                    "The review repository failed to commit the decision");
        }
    }

    // ------------------------------------------------------------------ failure helpers

    private static EvaluationException invalidDecision() {
        return new EvaluationException(
                EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                "The review snapshot does not match the exact case key that was submitted");
    }

    private static EvaluationException authorizationDenied() {
        return new EvaluationException(
                EvaluationErrorCodes.REVIEW_AUTHORIZATION_DENIED,
                "Trusted review authorization did not approve this exact actor, role and case key");
    }

    private static <T> T require(T dependency, String invariant) {
        if (dependency == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "Review decision service dependency missing: " + invariant);
        }
        return dependency;
    }
}