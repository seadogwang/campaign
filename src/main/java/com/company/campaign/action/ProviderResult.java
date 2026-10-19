package com.company.campaign.action;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One immutable, hash-bound Provider result: what one actual Provider call established about the remote effect.
 *
 * <p>It is exactly the accepted v1.3 result semantics plus the bounded evidence they may carry. A
 * {@code SUCCEEDED} result requires a bounded Provider reference, which is the same reference a later
 * reconciliation must corroborate; a {@code DEFINITELY_NOT_APPLIED}, {@code PENDING} or {@code UNKNOWN} result
 * carries no Provider reference and no accepted instant, because claiming either would claim a remote fact the
 * outcome denies. Every result carries a bounded machine reason code and never a Provider message, a token, a
 * URL or an unrestricted exception text.
 *
 * <p>{@link #toString()} prints no Provider reference and no Provider reason text.
 */
public record ProviderResult(
        String formatVersion,
        String resultId,
        String idempotencyKey,
        ProviderOutcome outcome,
        String providerReference,
        Instant acceptedInstant,
        String reasonCode,
        ContentHash resultHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ProviderResult {
        if (!ActionVersions.PROVIDER_RESULT_FORMAT_VERSION.equals(formatVersion)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_VERSION_UNSUPPORTED, "formatVersion");
        }
        ActionSupport.requireIdentifier(resultId, "resultId");
        ActionSupport.requireKey(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(outcome, "outcome");
        ActionSupport.requireOptionalIdentifier(providerReference, "providerReference");
        ActionSupport.requireMachineCode(reasonCode, "reasonCode");
        if (outcome.applied() != (providerReference != null)) {
            // Applied means a bounded Provider reference exists; anything else must not claim one.
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "providerReference");
        }
        if (!outcome.applied() && acceptedInstant != null) {
            // An instant may only be evidence of an accepted effect, never of a missing one.
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "acceptedInstant");
        }
        Objects.requireNonNull(resultHash, "resultHash");
        if (!resultHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, resultId, idempotencyKey, outcome, providerReference, acceptedInstant,
                reasonCode)))) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "resultHash");
        }
    }

    /** One bounded result carrying its own canonical hash. */
    public static ProviderResult of(
            String idempotencyKey,
            ProviderOutcome outcome,
            String providerReference,
            Instant acceptedInstant,
            String reasonCode) {
        String resultId = ActionSupport.derivedId("prvres", List.of(
                ActionVersions.PROVIDER_RESULT_FORMAT_VERSION, idempotencyKey, outcome.name(),
                providerReference == null ? "" : providerReference,
                acceptedInstant == null ? "" : acceptedInstant.toString(), reasonCode));
        return new ProviderResult(
                ActionVersions.PROVIDER_RESULT_FORMAT_VERSION, resultId, idempotencyKey, outcome,
                providerReference, acceptedInstant, reasonCode,
                CANONICAL_JSON.hash(material(
                        ActionVersions.PROVIDER_RESULT_FORMAT_VERSION, resultId, idempotencyKey, outcome,
                        providerReference, acceptedInstant, reasonCode)));
    }

    /** One applied result, with the bounded Provider reference and accepted instant it evidences. */
    public static ProviderResult succeeded(
            String idempotencyKey, String providerReference, Instant acceptedInstant, String reasonCode) {
        return of(idempotencyKey, ProviderOutcome.SUCCEEDED, providerReference, acceptedInstant, reasonCode);
    }

    /** One result proving the effect is definitively not applied. */
    public static ProviderResult definitivelyNotApplied(String idempotencyKey, String reasonCode) {
        return of(idempotencyKey, ProviderOutcome.DEFINITELY_NOT_APPLIED, null, null, reasonCode);
    }

    /** One result leaving the effect pending remotely. */
    public static ProviderResult pending(String idempotencyKey, String reasonCode) {
        return of(idempotencyKey, ProviderOutcome.PENDING, null, null, reasonCode);
    }

    /** One result that could not establish the remote state at all. */
    public static ProviderResult unknown(String idempotencyKey, String reasonCode) {
        return of(idempotencyKey, ProviderOutcome.UNKNOWN, null, null, reasonCode);
    }

    private static Material material(
            String formatVersion,
            String resultId,
            String idempotencyKey,
            ProviderOutcome outcome,
            String providerReference,
            Instant acceptedInstant,
            String reasonCode) {
        return new Material(formatVersion, resultId, idempotencyKey, outcome, providerReference, acceptedInstant,
                reasonCode);
    }

    /** Redacted diagnostics: no Provider reference is printed. */
    @Override
    public String toString() {
        return "ProviderResult[resultId=" + resultId
                + ", outcome=" + outcome
                + ", reasonCode=" + reasonCode
                + ", acceptedInstant=" + acceptedInstant
                + ", resultHash=" + resultHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String resultId,
            String idempotencyKey,
            ProviderOutcome outcome,
            String providerReference,
            Instant acceptedInstant,
            String reasonCode) {
    }
}