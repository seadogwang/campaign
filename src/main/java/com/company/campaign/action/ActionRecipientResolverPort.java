package com.company.campaign.action;

import java.util.Optional;

/**
 * The narrow host port through which one action resolves its recipient target.
 *
 * <p>It is a host-owned lookup keyed by the exact persisted owner and frozen node identity, never by a
 * caller-supplied address, a raw member profile field or an ambient security context. It deliberately returns
 * an opaque bounded reference and an evidence hash rather than an address, so no recipient value can enter the
 * action layer at all.
 *
 * <p>It is always called with no store lock or transaction held, and an empty or failing answer fails closed
 * before anything is reserved: an unresolvable recipient is never an action this build may submit.
 */
public interface ActionRecipientResolverPort {

    /**
     * The host-resolved recipient target of one frozen action node, or empty when the host can name none.
     *
     * @param tenantId the restored owner, never a caller-supplied authority
     * @param participantId the exact persisted Participant identity
     * @param nodeLogicalId the exact frozen node logical identity
     * @param actionKind the closed kind of external action
     * @param channelCode the frozen message channel, or null for a reward
     */
    Optional<ActionRecipientTarget> resolve(
            String tenantId,
            String participantId,
            String nodeLogicalId,
            ActionKind actionKind,
            String channelCode);
}