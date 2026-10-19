package com.company.campaign.builder.proposal;

import com.company.campaign.builder.compiler.CapabilityPayload;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One ordered operation of a CampaignProposalPatch.
 *
 * <p>The permitted field set is decided by {@link #kind()}, not by the caller:
 *
 * <ul>
 *   <li>{@code UPSERT} carries a capability code, exactly one identity form and a payload;</li>
 *   <li>{@code DELETE} carries an existing {@code logicalId} and nothing else — no payload, no
 *       {@code clientRef}, so a delete can never smuggle a replacement value;</li>
 *   <li>{@code MOVE} carries exactly one identity form and exactly one relative anchor, and no payload, so
 *       relocation can never be expressed as a delete plus a re-create.</li>
 * </ul>
 *
 * <p>Nothing about a trusted tenant, program, user, membership, DataScope, permission, revision, lifecycle
 * state, plan, Manifest, ChangeSet, storage, timestamp, credential or Apply/Publish flag is representable
 * here or inside {@link #value()}.
 */
public record ProposalOperation(
        ProposalOperationKind kind,
        String capabilityCode,
        ProposalIdentity target,
        CapabilityPayload value,
        ProposalMoveAnchor anchor) {

    private static final Pattern CAPABILITY = Pattern.compile("^[a-z][a-z0-9.-]{0,63}$");

    public ProposalOperation {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
        switch (kind) {
            case UPSERT -> {
                if (capabilityCode == null || !CAPABILITY.matcher(capabilityCode).matches()) {
                    throw ProposalException.of(ProposalErrorCodes.PROPOSAL_IDENTITY_INVALID);
                }
                if (value == null || anchor != null) {
                    throw ProposalException.of(ProposalErrorCodes.PROPOSAL_IDENTITY_INVALID);
                }
            }
            case DELETE -> {
                if (capabilityCode != null || value != null || anchor != null) {
                    throw ProposalException.of(ProposalErrorCodes.PROPOSAL_IDENTITY_INVALID);
                }
                if (!target.isLogicalId()) {
                    // A delete addresses an existing object; a clientRef never names one.
                    throw ProposalException.of(ProposalErrorCodes.PROPOSAL_IDENTITY_INVALID);
                }
            }
            case MOVE -> {
                if (capabilityCode != null || value != null) {
                    throw ProposalException.of(ProposalErrorCodes.PROPOSAL_IDENTITY_INVALID);
                }
                if (anchor == null) {
                    throw ProposalException.of(ProposalErrorCodes.PROPOSAL_ANCHOR_INVALID);
                }
            }
        }
    }

    /** A new-object upsert addressed by {@code clientRef}. */
    public static ProposalOperation upsertNew(String capabilityCode, String clientRef, CapabilityPayload value) {
        return new ProposalOperation(
                ProposalOperationKind.UPSERT, capabilityCode, ProposalIdentity.ofClientRef(clientRef), value, null);
    }

    /** An existing-object upsert addressed by its stable {@code logicalId}. */
    public static ProposalOperation upsertExisting(
            String capabilityCode, String logicalId, CapabilityPayload value) {
        return new ProposalOperation(
                ProposalOperationKind.UPSERT, capabilityCode, ProposalIdentity.ofLogicalId(logicalId), value, null);
    }

    /** An explicit delete of an existing object. */
    public static ProposalOperation delete(String logicalId) {
        return new ProposalOperation(
                ProposalOperationKind.DELETE, null, ProposalIdentity.ofLogicalId(logicalId), null, null);
    }

    /** A relative relocation of one object against exactly one stable anchor value. */
    public static ProposalOperation move(ProposalIdentity target, MoveAnchorKind anchorKind, String anchorValue) {
        return new ProposalOperation(
                ProposalOperationKind.MOVE, null, target, null, new ProposalMoveAnchor(anchorKind, anchorValue));
    }

    @Override
    public String toString() {
        return "ProposalOperation[kind=" + kind + ", target=" + target
                + ", capability=" + (capabilityCode == null ? "-" : capabilityCode) + ']';
    }
}