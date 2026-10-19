package com.company.campaign.action;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One immutable ActionAttempt: the durable record of one actual Provider submission or one actual
 * reconciliation query.
 *
 * <p>The attempt number counts actual Provider calls, never scheduled ones, and the attempt identity is derived
 * from the action, the attempt kind and that number, so a retry can never silently overwrite the evidence of an
 * earlier try. {@link #outcomeCode()} is a bounded machine code and {@link #providerReference()} is a bounded
 * Provider reference, so an applied effect, a definitive non-application, a pending effect and an unknown
 * outcome stay distinguishable without parsing a message.
 *
 * <p>It never carries a Provider token, a URL, an endpoint, a member value, a request field or an unrestricted
 * exception message. {@link #toString()} prints neither the Provider reference nor any owner identity.
 */
public record ActionAttempt(
        String formatVersion,
        String attemptId,
        String tenantId,
        String actionId,
        String idempotencyKey,
        ActionAttemptKind attemptKind,
        int attemptNumber,
        ProviderOutcome outcome,
        String outcomeCode,
        String providerReference,
        Instant acceptedInstant,
        Instant startedAt,
        Instant completedAt,
        ContentHash attemptHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ActionAttempt {
        if (!ActionVersions.ACTION_ATTEMPT_FORMAT_VERSION.equals(formatVersion)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_VERSION_UNSUPPORTED, "formatVersion");
        }
        ActionSupport.requireIdentifier(attemptId, "attemptId");
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireIdentifier(actionId, "actionId");
        ActionSupport.requireKey(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(attemptKind, "attemptKind");
        if (attemptNumber < 1) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "attemptNumber");
        }
        Objects.requireNonNull(outcome, "outcome");
        ActionSupport.requireMachineCode(outcomeCode, "outcomeCode");
        ActionSupport.requireOptionalIdentifier(providerReference, "providerReference");
        if (outcome.applied() != (providerReference != null)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "providerReference");
        }
        if (!outcome.applied() && acceptedInstant != null) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "acceptedInstant");
        }
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        if (completedAt.isBefore(startedAt)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "completedAt");
        }
        Objects.requireNonNull(attemptHash, "attemptHash");
        if (!attemptHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, attemptId, tenantId, actionId, idempotencyKey, attemptKind, attemptNumber,
                outcome, outcomeCode, providerReference, acceptedInstant, startedAt, completedAt)))) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "attemptHash");
        }
    }

    /** Records one actual Provider attempt of one action. */
    public static ActionAttempt of(
            String tenantId,
            String actionId,
            String idempotencyKey,
            ActionAttemptKind attemptKind,
            int attemptNumber,
            ProviderResult result,
            String outcomeCode,
            Instant startedAt,
            Instant completedAt) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(completedAt, "completedAt");
        String attemptId = attemptId(actionId, attemptKind, attemptNumber);
        String reference = result.outcome().applied() ? result.providerReference() : null;
        Instant accepted = result.outcome().applied() ? result.acceptedInstant() : null;
        return new ActionAttempt(
                ActionVersions.ACTION_ATTEMPT_FORMAT_VERSION, attemptId, tenantId, actionId, idempotencyKey,
                attemptKind, attemptNumber, result.outcome(), outcomeCode, reference, accepted, startedAt,
                completedAt,
                CANONICAL_JSON.hash(material(
                        ActionVersions.ACTION_ATTEMPT_FORMAT_VERSION, attemptId, tenantId, actionId,
                        idempotencyKey, attemptKind, attemptNumber, result.outcome(), outcomeCode, reference,
                        accepted, startedAt, completedAt)));
    }

    /** The one deterministic attempt identity of one actual Provider call. */
    public static String attemptId(String actionId, ActionAttemptKind attemptKind, int attemptNumber) {
        return ActionSupport.derivedId("actatt", List.of(
                ActionVersions.ACTION_ATTEMPT_ID_VERSION, actionId, attemptKind.code(), attemptNumber));
    }

    private static Material material(
            String formatVersion,
            String attemptId,
            String tenantId,
            String actionId,
            String idempotencyKey,
            ActionAttemptKind attemptKind,
            int attemptNumber,
            ProviderOutcome outcome,
            String outcomeCode,
            String providerReference,
            Instant acceptedInstant,
            Instant startedAt,
            Instant completedAt) {
        return new Material(formatVersion, attemptId, tenantId, actionId, idempotencyKey, attemptKind,
                attemptNumber, outcome, outcomeCode, providerReference, acceptedInstant, startedAt, completedAt);
    }

    /** Redacted diagnostics: neither the Provider reference nor an owner identity is printed. */
    @Override
    public String toString() {
        return "ActionAttempt[attemptId=" + attemptId
                + ", attemptKind=" + attemptKind
                + ", attemptNumber=" + attemptNumber
                + ", outcome=" + outcome
                + ", outcomeCode=" + outcomeCode
                + ", completedAt=" + completedAt
                + ", attemptHash=" + attemptHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String attemptId,
            String tenantId,
            String actionId,
            String idempotencyKey,
            ActionAttemptKind attemptKind,
            int attemptNumber,
            ProviderOutcome outcome,
            String outcomeCode,
            String providerReference,
            Instant acceptedInstant,
            Instant startedAt,
            Instant completedAt) {
    }
}