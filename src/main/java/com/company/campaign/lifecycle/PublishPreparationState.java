package com.company.campaign.lifecycle;

/**
 * The lifecycle state of one server-stored publish preparation.
 *
 * <p>A preparation is the only authority a Publish confirmation may present, so its state is the whole of
 * what may be published: exactly {@link #PREPARED} permits a confirmation to freeze a Published Definition,
 * and every other state fails closed with its own stable reason code.
 *
 * <p>Two of the four states are durable — {@link #PREPARED} and {@link #CONSUMED} are what the store
 * recorded — while {@link #EXPIRED} and {@link #STALE} are decided when the preparation is consulted, from
 * the preparation's own expiry instant and from the host state the confirmation re-reads. Classification is
 * therefore total and never inferred from a caller.
 */
public enum PublishPreparationState {

    /** Prepared, unexpired and still exactly consistent with the saved Draft and its frozen inputs. */
    PREPARED,

    /** Already consumed by one successful Publish; a preparation is consumed at most once. */
    CONSUMED,

    /** At or past its expiry instant: the frozen evidence is no longer current enough to publish. */
    EXPIRED,

    /** The saved Draft, its root revisions, the caller's DataScope or a frozen input has drifted. */
    STALE;

    /** True only for {@link #PREPARED}, the single state a confirmation may freeze from. */
    public boolean publishable() {
        return this == PREPARED;
    }
}