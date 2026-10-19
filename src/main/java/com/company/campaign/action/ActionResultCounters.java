package com.company.campaign.action;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.List;
import java.util.Objects;

/**
 * The immutable, revisioned aggregate action-result projection of one Campaign Execution.
 *
 * <p>It is the additive T23 companion of the accepted T22 execution counters, whose canonical hash and
 * semantics are deliberately left untouched. It exists because the accepted result metrics have to distinguish
 * things a node status can never imply: how many messages were actually accepted, how many rewards were
 * actually granted, how many actions failed definitively, and how many entered an unresolved reconciliation.
 *
 * <p>A message acceptance is counted from a {@code MESSAGE} action that a Provider confirmed applied, never
 * from a node that merely succeeded. A reward grant is counted from a {@code REWARD} action that a Provider
 * confirmed applied, never from a node that succeeded and never from a message. That is what makes "a
 * successful node does not automatically mean a reward was granted" a property of the projection rather than a
 * comment. {@link #toString()} prints no member, tenant, Campaign or Execution identity.
 */
public record ActionResultCounters(
        String formatVersion,
        String tenantId,
        String executionId,
        int messagesAccepted,
        int rewardsSucceeded,
        int definiteFailures,
        int unknownActions,
        int needsReviewActions,
        long revision,
        ContentHash countersHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ActionResultCounters {
        if (!ActionVersions.RESULT_COUNTERS_FORMAT_VERSION.equals(formatVersion)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_VERSION_UNSUPPORTED, "formatVersion");
        }
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireIdentifier(executionId, "executionId");
        ActionSupport.requireCount(messagesAccepted, ActionVersions.MAX_ACTION_RECORDS, "messagesAccepted");
        ActionSupport.requireCount(rewardsSucceeded, ActionVersions.MAX_ACTION_RECORDS, "rewardsSucceeded");
        ActionSupport.requireCount(definiteFailures, ActionVersions.MAX_ACTION_RECORDS, "definiteFailures");
        ActionSupport.requireCount(unknownActions, ActionVersions.MAX_ACTION_RECORDS, "unknownActions");
        ActionSupport.requireCount(needsReviewActions, ActionVersions.MAX_ACTION_RECORDS, "needsReviewActions");
        ActionSupport.requireNonNegative(revision, "revision");
        Objects.requireNonNull(countersHash, "countersHash");
        if (!countersHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, tenantId, executionId, messagesAccepted, rewardsSucceeded, definiteFailures,
                unknownActions, needsReviewActions, revision)))) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "countersHash");
        }
    }

    /** The empty projection of one Execution. */
    public static ActionResultCounters empty(String tenantId, String executionId) {
        return build(tenantId, executionId, 0, 0, 0, 0, 0, 0L);
    }

    /** The same projection with one confirmed message acceptance. */
    public ActionResultCounters messageAccepted() {
        return build(tenantId, executionId, messagesAccepted + 1, rewardsSucceeded, definiteFailures,
                unknownActions, needsReviewActions, revision + 1L);
    }

    /** The same projection with one confirmed reward grant. */
    public ActionResultCounters rewardSucceeded() {
        return build(tenantId, executionId, messagesAccepted, rewardsSucceeded + 1, definiteFailures,
                unknownActions, needsReviewActions, revision + 1L);
    }

    /** The same projection with one definitive action failure. */
    public ActionResultCounters definiteFailure() {
        return build(tenantId, executionId, messagesAccepted, rewardsSucceeded, definiteFailures + 1,
                unknownActions, needsReviewActions, revision + 1L);
    }

    /** The same projection with one action entering an unresolved reconciliation. */
    public ActionResultCounters unknownAction() {
        return build(tenantId, executionId, messagesAccepted, rewardsSucceeded, definiteFailures,
                unknownActions + 1, needsReviewActions, revision + 1L);
    }

    /** The same projection with one action that exhausted its bounded verification policy. */
    public ActionResultCounters needsReviewAction() {
        return build(tenantId, executionId, messagesAccepted, rewardsSucceeded, definiteFailures, unknownActions,
                needsReviewActions + 1, revision + 1L);
    }

    /** The same projection at one exact declared state; used by a bounded move. */
    public ActionResultCounters at(
            int newMessagesAccepted,
            int newRewardsSucceeded,
            int newDefiniteFailures,
            int newUnknownActions,
            int newNeedsReviewActions) {
        return build(tenantId, executionId, newMessagesAccepted, newRewardsSucceeded, newDefiniteFailures,
                newUnknownActions, newNeedsReviewActions, revision + 1L);
    }

    /** The same projection with one exact disposition counted, or itself when nothing changed. */
    public ActionResultCounters counted(
            ActionKind kind, ActionStatus from, ActionStatus to) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(to, "to");
        if (to == from) {
            return this;
        }
        return switch (to) {
            case SUCCEEDED -> kind == ActionKind.MESSAGE ? messageAccepted() : rewardSucceeded();
            case DEFINITELY_FAILED -> definiteFailure();
            case VERIFYING -> unknownAction();
            case NEEDS_REVIEW -> needsReviewAction();
            default -> this;
        };
    }

    private static ActionResultCounters build(
            String tenantId,
            String executionId,
            int messagesAccepted,
            int rewardsSucceeded,
            int definiteFailures,
            int unknownActions,
            int needsReviewActions,
            long revision) {
        return new ActionResultCounters(
                ActionVersions.RESULT_COUNTERS_FORMAT_VERSION, tenantId, executionId, messagesAccepted,
                rewardsSucceeded, definiteFailures, unknownActions, needsReviewActions, revision,
                CANONICAL_JSON.hash(material(
                        ActionVersions.RESULT_COUNTERS_FORMAT_VERSION, tenantId, executionId, messagesAccepted,
                        rewardsSucceeded, definiteFailures, unknownActions, needsReviewActions, revision)));
    }

    private static Material material(
            String formatVersion,
            String tenantId,
            String executionId,
            int messagesAccepted,
            int rewardsSucceeded,
            int definiteFailures,
            int unknownActions,
            int needsReviewActions,
            long revision) {
        return new Material(formatVersion, tenantId, executionId, messagesAccepted, rewardsSucceeded,
                definiteFailures, unknownActions, needsReviewActions, revision);
    }

    /** Redacted diagnostics: no member, tenant, Campaign or Execution identity is printed. */
    @Override
    public String toString() {
        return "ActionResultCounters[messagesAccepted=" + messagesAccepted
                + ", rewardsSucceeded=" + rewardsSucceeded
                + ", definiteFailures=" + definiteFailures
                + ", unknownActions=" + unknownActions
                + ", needsReviewActions=" + needsReviewActions
                + ", revision=" + revision
                + ", countersHash=" + countersHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String tenantId,
            String executionId,
            int messagesAccepted,
            int rewardsSucceeded,
            int definiteFailures,
            int unknownActions,
            int needsReviewActions,
            long revision) {
    }

    /** The closed result categories this projection distinguishes. */
    public static List<String> categories() {
        return List.of("MESSAGES_ACCEPTED", "REWARDS_SUCCEEDED", "DEFINITE_FAILURES", "UNKNOWN_ACTIONS",
                "NEEDS_REVIEW_ACTIONS");
    }
}