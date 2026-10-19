package com.company.campaign.builder.capability.pack;

import com.company.campaign.builder.compiler.CapabilityContract;

/**
 * The narrow extension of the accepted {@link CapabilityContract} a complete frozen capability pack
 * implements.
 *
 * <p>It adds exactly one read-only fact to the generic contract — the immutable
 * {@link CapabilityPackMetadata} describing the pack — and changes nothing else about resolution. The
 * generic compiler stays capability-agnostic: it still resolves a payload through
 * {@link CapabilityContract#resolve} and never has to interpret a pack's metadata to do so. Later
 * validation, preview or tooling can read the metadata without the compiler becoming capability-aware.
 *
 * <p>The interface declares no behavior, no repository, no provider and no mutable handle, so a pack that
 * implements it supplies frozen facts only.
 */
public interface CapabilityPackContract extends CapabilityContract {

    /** The complete, immutable, machine-readable metadata of this frozen pack. */
    CapabilityPackMetadata packMetadata();
}