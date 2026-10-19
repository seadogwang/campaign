package com.company.campaign.action;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.List;
import java.util.Objects;

/**
 * One immutable, hash-bound statement of what a Provider can actually guarantee.
 *
 * <p>It states exactly the two facts this layer refuses to assume: whether the Provider honours a stable
 * business key so one key can never produce two remote effects, and whether it can reliably answer
 * {@code queryByKey} so a possibly-applied effect can be reconciled. It also names its own owner, the action
 * kind it serves and whether it is a production Provider at all.
 *
 * <p>{@link #satisfiesSubmission()} is the fail-closed gate every submission crosses <em>before</em> it calls a
 * Provider: a Provider that lacks business-key idempotency or reliable reconciliation is refused, because a
 * local database lock is never a substitute for a remote guarantee. {@link #satisfiesLiveReady()} is
 * deliberately stricter and additionally requires a production Provider, so a deterministic simulated Provider
 * — which always declares {@code production=false} — can never satisfy {@code LIVE_READY}, however complete its
 * fixture evidence is.
 *
 * <p>{@link #toString()} prints no credential, token, URL or endpoint.
 */
public record ProviderCapability(
        String formatVersion,
        String providerId,
        String ownerTenantId,
        ActionKind actionKind,
        boolean businessKeyIdempotency,
        boolean reliableQueryByKey,
        boolean production,
        ContentHash capabilityHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ProviderCapability {
        if (!ActionVersions.PROVIDER_CAPABILITY_FORMAT_VERSION.equals(formatVersion)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_VERSION_UNSUPPORTED, "formatVersion");
        }
        ActionSupport.requireIdentifier(providerId, "providerId");
        ActionSupport.requireIdentifier(ownerTenantId, "ownerTenantId");
        Objects.requireNonNull(actionKind, "actionKind");
        Objects.requireNonNull(capabilityHash, "capabilityHash");
        if (!capabilityHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, providerId, ownerTenantId, actionKind, businessKeyIdempotency, reliableQueryByKey,
                production)))) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "capabilityHash");
        }
    }

    /** Builds one capability statement and stamps it with its own canonical hash. */
    public static ProviderCapability of(
            String providerId,
            String ownerTenantId,
            ActionKind actionKind,
            boolean businessKeyIdempotency,
            boolean reliableQueryByKey,
            boolean production) {
        return new ProviderCapability(
                ActionVersions.PROVIDER_CAPABILITY_FORMAT_VERSION, providerId, ownerTenantId, actionKind,
                businessKeyIdempotency, reliableQueryByKey, production,
                CANONICAL_JSON.hash(material(
                        ActionVersions.PROVIDER_CAPABILITY_FORMAT_VERSION, providerId, ownerTenantId, actionKind,
                        businessKeyIdempotency, reliableQueryByKey, production)));
    }

    /** True only when this Provider may be submitted to at all. */
    public boolean satisfiesSubmission() {
        return businessKeyIdempotency && reliableQueryByKey;
    }

    /**
     * True only when this Provider's own evidence could ever be part of a {@code LIVE_READY} claim.
     *
     * <p>A simulated Provider declares {@code production=false}, so this is false for every Provider T23
     * ships: its evidence is explicit non-production fixture evidence, never a production gate.
     */
    public boolean satisfiesLiveReady() {
        return satisfiesSubmission() && production;
    }

    /** True when this capability is explicit non-production fixture evidence. */
    public boolean fixtureEvidence() {
        return !production;
    }

    private static Material material(
            String formatVersion,
            String providerId,
            String ownerTenantId,
            ActionKind actionKind,
            boolean businessKeyIdempotency,
            boolean reliableQueryByKey,
            boolean production) {
        return new Material(formatVersion, providerId, ownerTenantId, actionKind, businessKeyIdempotency,
                reliableQueryByKey, production);
    }

    /** Redacted diagnostics: no credential, token, URL or endpoint is printed. */
    @Override
    public String toString() {
        return "ProviderCapability[providerId=" + providerId
                + ", actionKind=" + actionKind
                + ", businessKeyIdempotency=" + businessKeyIdempotency
                + ", reliableQueryByKey=" + reliableQueryByKey
                + ", production=" + production
                + ", capabilityHash=" + capabilityHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String providerId,
            String ownerTenantId,
            ActionKind actionKind,
            boolean businessKeyIdempotency,
            boolean reliableQueryByKey,
            boolean production) {
    }
}