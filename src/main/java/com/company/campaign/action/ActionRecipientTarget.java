package com.company.campaign.action;

import com.company.campaign.shared.canonical.ContentHash;

import java.util.Objects;

/**
 * One host-resolved recipient target: an opaque bounded reference plus a hash of the host evidence that
 * produced it.
 *
 * <p>It carries no member profile, no address, no phone number, no e-mail and no template variable. The
 * reference is what a Provider SPI is handed; the evidence hash is what the frozen request binds, so the exact
 * recipient evidence one request was resolved for stays provable from the persisted record without the record
 * ever holding the address itself. {@link #toString()} prints neither.
 */
public record ActionRecipientTarget(
        String recipientRef,
        ContentHash evidenceHash
) {

    public ActionRecipientTarget {
        ActionSupport.requireIdentifier(recipientRef, "recipientRef");
        Objects.requireNonNull(evidenceHash, "evidenceHash");
    }

    /** True when this target is the exact evidence another target states. */
    public boolean witnesses(ActionRecipientTarget other) {
        return other != null && recipientRef.equals(other.recipientRef)
                && evidenceHash.equals(other.evidenceHash);
    }

    /** Redacted diagnostics: neither the reference nor the evidence hash is printed. */
    @Override
    public String toString() {
        return "ActionRecipientTarget[evidence=present]";
    }
}