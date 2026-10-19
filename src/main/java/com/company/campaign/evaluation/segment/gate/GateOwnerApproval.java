package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.contracts.ContractPatterns;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * One accountable owner approval inside a Gate approval quorum.
 *
 * <p>It carries only an opaque bounded owner reference, the owner role and the explicit host-supplied
 * approval time. No email address, identity, tenant/user/DataScope fact, credential or free note can be
 * represented here, so the quorum evidence stays bounded audit material.
 */
public record GateOwnerApproval(
        String ownerRef,
        GateOwnerRole ownerRole,
        Instant approvedAt
) {

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public GateOwnerApproval {
        if (ownerRef == null || !REFERENCE_ID.matcher(ownerRef).matches() || ownerRole == null
                || approvedAt == null) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_INVALID,
                    "A Gate owner approval requires one bounded opaque owner reference, role and explicit time");
        }
    }
}