package com.company.campaign.evaluation.segment.review.testsupport;

import com.company.campaign.evaluation.segment.contract.ReviewRole;
import com.company.campaign.evaluation.segment.review.ReviewActor;
import com.company.campaign.evaluation.segment.review.ReviewAuthorizationDecision;
import com.company.campaign.evaluation.segment.review.ReviewAuthorizationPort;
import com.company.campaign.evaluation.segment.review.ReviewCaseKey;

import java.util.ArrayList;
import java.util.List;

/**
 * A recording review-authorization port that can answer with every non-exact shape the service must refuse.
 *
 * <p>It records each call and can echo an approval for a different role or case, deny, return null or throw,
 * so a test can prove the service fails closed without reaching the genuine host policy.
 */
public final class RecordingReviewAuthorizationPort implements ReviewAuthorizationPort {

    private enum Mode {
        ALLOW,
        DENY,
        NULL,
        WRONG_ROLE,
        WRONG_CASE_DECISION,
        THROW
    }

    private final Mode mode;
    private final ReviewRole wrongRole;
    private final ReviewCaseKey wrongCaseKey;
    private final List<ReviewActor> actors = new ArrayList<>();
    private final List<ReviewRole> roles = new ArrayList<>();
    private final List<ReviewCaseKey> keys = new ArrayList<>();
    private int callCount;

    private RecordingReviewAuthorizationPort(Mode mode, ReviewRole wrongRole, ReviewCaseKey wrongCaseKey) {
        this.mode = mode;
        this.wrongRole = wrongRole;
        this.wrongCaseKey = wrongCaseKey;
    }

    /** An exact approval of whatever the service asks. */
    public static RecordingReviewAuthorizationPort allowing() {
        return new RecordingReviewAuthorizationPort(Mode.ALLOW, null, null);
    }

    /** An explicit denial. */
    public static RecordingReviewAuthorizationPort denying() {
        return new RecordingReviewAuthorizationPort(Mode.DENY, null, null);
    }

    /** A boundary that returns no decision at all. */
    public static RecordingReviewAuthorizationPort returningNull() {
        return new RecordingReviewAuthorizationPort(Mode.NULL, null, null);
    }

    /** An approval that echoes a different role than the one requested. */
    public static RecordingReviewAuthorizationPort allowingWrongRole(ReviewRole echoed) {
        return new RecordingReviewAuthorizationPort(Mode.WRONG_ROLE, echoed, null);
    }

    /** An approval that echoes a different case key than the one requested. */
    public static RecordingReviewAuthorizationPort allowingWrongCase(ReviewCaseKey echoed) {
        return new RecordingReviewAuthorizationPort(Mode.WRONG_CASE_DECISION, null, echoed);
    }

    /** A boundary that throws a secret-shaped failure. */
    public static RecordingReviewAuthorizationPort throwing() {
        return new RecordingReviewAuthorizationPort(Mode.THROW, null, null);
    }

    @Override
    public ReviewAuthorizationDecision authorize(ReviewActor actor, ReviewRole role, ReviewCaseKey caseKey) {
        callCount++;
        actors.add(actor);
        roles.add(role);
        keys.add(caseKey);
        return switch (mode) {
            case ALLOW -> ReviewAuthorizationDecision.allow(actor, role, caseKey);
            case DENY -> ReviewAuthorizationDecision.deny(actor, role, caseKey);
            case NULL -> null;
            case WRONG_ROLE -> ReviewAuthorizationDecision.allow(actor, wrongRole, caseKey);
            case WRONG_CASE_DECISION -> ReviewAuthorizationDecision.allow(actor, role, wrongCaseKey);
            case THROW -> throw new IllegalStateException("authorization failure with secret-like text");
        };
    }

    public int callCount() {
        return callCount;
    }

    public List<ReviewRole> requestedRoles() {
        return List.copyOf(roles);
    }

    public List<ReviewCaseKey> requestedKeys() {
        return List.copyOf(keys);
    }

    public List<ReviewActor> requestedActors() {
        return List.copyOf(actors);
    }
}