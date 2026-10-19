package com.company.campaign.runtime;

/**
 * The one host-owned port through which Runtime obtains a fixed-{@code asOf} audience.
 *
 * <p>It is a single method taking the bounded {@link AudienceMaterializationRequest} and returning the bounded
 * {@link AudienceMaterializationResult}: there is no overload that accepts raw SQL, a member list, a readiness
 * declaration or an unrestricted filter, and no streaming or paging variant, because a paged answer is exactly
 * the silently truncated set this contract refuses to represent.
 *
 * <p>The host is allowed to answer slowly or to refuse; it is not allowed to answer partially. Timeout,
 * staleness, query failure and DataScope refusal are expressed as closed outcomes rather than as exceptions,
 * so the consumer can persist a bounded failure reason instead of an unrestricted message.
 */
public interface AudienceMaterializationPort {

    /** Answers one bounded audience query for one exact frozen definition and one fixed evaluation instant. */
    AudienceMaterializationResult materialize(AudienceMaterializationRequest request);
}