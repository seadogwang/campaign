package com.company.campaign.action;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One revisioned, hash-bound BudgetReservation: the exact conservative reward exposure one REWARD action holds
 * under its own business idempotency key.
 *
 * <p>It is created before the Provider is ever invoked and it is bound to one action and one key, so every
 * retry, reclaim, restart and reconciliation reuses exactly this reservation and can never reserve the same
 * exposure twice. It remains held while the remote outcome is unknown or under verification, is finalized
 * exactly once when the effect is confirmed applied, and is released exactly once only after definitive
 * non-application or definitive failure. It remains held forever for a {@code NEEDS_REVIEW} action, because a
 * remote effect may still exist.
 *
 * <p>{@link #toString()} prints no member, tenant, Execution or reward identity.
 */
public record BudgetReservation(
        String formatVersion,
        String reservationId,
        String tenantId,
        String executionId,
        String actionId,
        String idempotencyKey,
        int grantCount,
        String currency,
        BigDecimal faceValue,
        BudgetReservationState state,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        ContentHash reservationHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public BudgetReservation {
        if (!ActionVersions.BUDGET_RESERVATION_FORMAT_VERSION.equals(formatVersion)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_VERSION_UNSUPPORTED, "formatVersion");
        }
        ActionSupport.requireIdentifier(reservationId, "reservationId");
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireIdentifier(executionId, "executionId");
        ActionSupport.requireIdentifier(actionId, "actionId");
        ActionSupport.requireKey(idempotencyKey, "idempotencyKey");
        if (grantCount < 1 || grantCount > (int) ActionVersions.MAX_REWARD_QUANTITY) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_BUDGET_INVALID, "grantCount");
        }
        ActionSupport.requireCurrency(currency, "currency");
        faceValue = ActionSupport.requireAmount(faceValue, "faceValue");
        Objects.requireNonNull(state, "state");
        ActionSupport.requireNonNegative(revision, "revision");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(reservationHash, "reservationHash");
        if (!reservationHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, reservationId, tenantId, executionId, actionId, idempotencyKey, grantCount,
                currency, faceValue, state, revision, createdAt, updatedAt)))) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "reservationHash");
        }
    }

    /** Reserves the exact conservative exposure of one REWARD action's grant. */
    public static BudgetReservation held(
            String tenantId,
            String executionId,
            String actionId,
            String idempotencyKey,
            int grantCount,
            String currency,
            BigDecimal faceValue,
            Instant at) {
        Objects.requireNonNull(at, "at");
        return build(reservationId(tenantId, idempotencyKey), tenantId, executionId, actionId, idempotencyKey,
                grantCount, currency, faceValue, BudgetReservationState.HELD, 0L, at, at);
    }

    /** The one deterministic reservation identity of one business key inside one tenant. */
    public static String reservationId(String tenantId, String idempotencyKey) {
        return ActionSupport.derivedId("actbud", List.of(
                ActionVersions.BUDGET_RESERVATION_ID_VERSION, tenantId, idempotencyKey));
    }

    /** The same reservation finalized exactly once because its remote effect is confirmed applied. */
    public BudgetReservation finalized(Instant at) {
        return transitioned(BudgetReservationState.FINALIZED, at);
    }

    /** The same reservation released exactly once because no remote effect exists. */
    public BudgetReservation released(Instant at) {
        return transitioned(BudgetReservationState.RELEASED, at);
    }

    /** The same reservation under one explicit, already-legal target state. */
    public BudgetReservation transitioned(BudgetReservationState target, Instant at) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(at, "at");
        if (!state.reaches(target)) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_BUDGET_CONFLICT, "reservationState",
                    "A reservation transition is not one the frozen budget lifecycle allows.");
        }
        return build(reservationId, tenantId, executionId, actionId, idempotencyKey, grantCount, currency,
                faceValue, target, revision + 1L, createdAt, at);
    }

    private static BudgetReservation build(
            String reservationId,
            String tenantId,
            String executionId,
            String actionId,
            String idempotencyKey,
            int grantCount,
            String currency,
            BigDecimal faceValue,
            BudgetReservationState state,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
        return new BudgetReservation(
                ActionVersions.BUDGET_RESERVATION_FORMAT_VERSION,
                reservationId, tenantId, executionId, actionId, idempotencyKey, grantCount, currency, faceValue,
                state, revision, createdAt, updatedAt,
                CANONICAL_JSON.hash(material(
                        ActionVersions.BUDGET_RESERVATION_FORMAT_VERSION, reservationId, tenantId, executionId,
                        actionId, idempotencyKey, grantCount, currency, faceValue, state, revision, createdAt,
                        updatedAt)));
    }

    private static Material material(
            String formatVersion,
            String reservationId,
            String tenantId,
            String executionId,
            String actionId,
            String idempotencyKey,
            int grantCount,
            String currency,
            BigDecimal faceValue,
            BudgetReservationState state,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
        return new Material(formatVersion, reservationId, tenantId, executionId, actionId, idempotencyKey,
                grantCount, currency, faceValue, state, revision, createdAt, updatedAt);
    }

    /** Redacted diagnostics: no member, tenant, Execution or reward identity is printed. */
    @Override
    public String toString() {
        return "BudgetReservation[reservationId=" + reservationId
                + ", grantCount=" + grantCount
                + ", state=" + state
                + ", revision=" + revision
                + ", reservationHash=" + reservationHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String reservationId,
            String tenantId,
            String executionId,
            String actionId,
            String idempotencyKey,
            int grantCount,
            String currency,
            BigDecimal faceValue,
            BudgetReservationState state,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
    }
}