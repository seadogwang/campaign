package com.company.campaign.runtime;

/**
 * The narrow production durability boundary of the Runtime store port.
 *
 * <p>It adds exactly one thing to {@link RuntimeStore}: an inspectable, closed
 * {@link RuntimeStoreDurability} claim. The production runtime host refuses every store that does not expose the
 * accepted {@link RuntimeStoreDurability#POSTGRESQL} claim, so a store cannot become production just by being
 * typed as a {@link RuntimeStore}: an in-memory registry is refused because it is not this type at all, and a
 * store that implements this type still passes only when its claim is accepted.
 *
 * <p>The claim describes the adapter and its storage mechanism only. It is never live-database readiness
 * evidence and never a substitute for an exercised PostgreSQL integration test.
 *
 * <p>This repository ships no PostgreSQL implementation of the large transactional {@link RuntimeStore} port.
 * The production runtime host therefore requires a host-supplied {@link DurableRuntimeStore} and never
 * substitutes, wraps or relabels an in-memory registry as one.
 */
public interface DurableRuntimeStore extends RuntimeStore {

    /** The closed durability claim of this store, or {@code null} when it asserts none. */
    RuntimeStoreDurability durability();
}