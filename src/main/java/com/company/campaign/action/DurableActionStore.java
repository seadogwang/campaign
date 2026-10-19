package com.company.campaign.action;

/**
 * The narrow production durability boundary of the Action store port.
 *
 * <p>It adds exactly one thing to {@link ActionStore}: an inspectable, closed {@link ActionStoreDurability}
 * claim. The production runtime host refuses every store that does not expose the accepted
 * {@link ActionStoreDurability#POSTGRESQL} claim, so a store cannot become production just by being typed as a
 * {@link ActionStore}: an in-memory registry is refused because it is not this type at all, and a store that
 * implements this type still passes only when its claim is accepted.
 *
 * <p>The claim describes the adapter and its storage mechanism only. It is never live-database readiness
 * evidence and never a substitute for an exercised PostgreSQL integration test.
 *
 * <p>This repository ships no PostgreSQL implementation of the large transactional {@link ActionStore} port.
 * The production runtime host therefore requires a host-supplied {@link DurableActionStore} and never
 * substitutes, wraps or relabels an in-memory registry as one.
 */
public interface DurableActionStore extends ActionStore {

    /** The closed durability claim of this store, or {@code null} when it asserts none. */
    ActionStoreDurability durability();
}