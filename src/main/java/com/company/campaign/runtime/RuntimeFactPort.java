package com.company.campaign.runtime;

/**
 * The one host-owned port through which Runtime reads one bounded fact for one Condition predicate.
 *
 * <p>It takes the bounded {@link RuntimeFactRequest} and returns the bounded {@link RuntimeFactResult}: there is
 * no overload that accepts raw SQL, an unrestricted filter, a member list or a trusted readiness declaration,
 * and no streaming or paging variant, because a partially answered fact is exactly the unresolved answer this
 * contract refuses to represent as a value.
 *
 * <p>The host is allowed to answer slowly or to refuse; it is not allowed to answer a different fact or a
 * different value kind. Timeout and unavailability are expressed as closed outcomes rather than as exceptions,
 * so the consumer persists a bounded reason code instead of an unrestricted message. This port is the only
 * external collaborator a Runtime worker calls, and it is never called while a store transaction is held.
 */
public interface RuntimeFactPort {

    /** Answers one bounded fact read for one exact frozen Condition predicate. */
    RuntimeFactResult readFact(RuntimeFactRequest request);
}