package com.company.campaign.runtime.testsupport;

import com.company.campaign.runtime.AudienceMaterializationOutcome;
import com.company.campaign.runtime.AudienceMaterializationPort;
import com.company.campaign.runtime.AudienceMaterializationRequest;
import com.company.campaign.runtime.AudienceMaterializationResult;
import com.company.campaign.runtime.RuntimeSupport;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.List;

/**
 * A deterministic, scriptable {@link AudienceMaterializationPort} for T22 tests.
 *
 * <p>It answers with exactly the evidence a test asks for, echoes the request's own freshness policy so a
 * well-formed answer is distinguishable from a host that answered under a different one, and records the last
 * request so a test can prove the fixed {@code asOf} and the frozen Segment pins the consumer actually sent.
 *
 * <p>It is a test fixture only: it holds no member data, reaches no database and fabricates no production
 * freshness claim beyond what a test explicitly states.
 */
public final class ScriptedAudiencePort implements AudienceMaterializationPort {

    /** One deterministic evidence identity a scripted answer is bound to. */
    public static final ContentHash EVIDENCE = RuntimeSupport.seedHash("t22-scripted-audience-evidence");

    /** One behaviour a scripted port answers with. */
    @FunctionalInterface
    public interface Behaviour {
        AudienceMaterializationResult answer(AudienceMaterializationRequest request);
    }

    private Behaviour behaviour;
    private AudienceMaterializationRequest lastRequest;
    private int calls;

    public ScriptedAudiencePort(Behaviour behaviour) {
        this.behaviour = java.util.Objects.requireNonNull(behaviour, "behaviour");
    }

    @Override
    public AudienceMaterializationResult materialize(AudienceMaterializationRequest request) {
        this.lastRequest = request;
        this.calls++;
        return behaviour.answer(request);
    }

    /** Replaces the behaviour this port answers with. */
    public void behave(Behaviour replaced) {
        this.behaviour = java.util.Objects.requireNonNull(replaced, "replaced");
    }

    /** The last request this port received, or {@code null} when it received none. */
    public AudienceMaterializationRequest lastRequest() {
        return lastRequest;
    }

    /** The number of times this port was called. */
    public int calls() {
        return calls;
    }

    // ------------------------------------------------------------------ behaviours

    /** A complete, distinct member set observed at one exact instant over one exact watermark. */
    public static Behaviour complete(
            String evidenceTenantId, List<String> members, Instant observedAt, Instant watermark) {
        List<String> copy = List.copyOf(members);
        return request -> AudienceMaterializationResult.of(
                AudienceMaterializationOutcome.COMPLETE, evidenceTenantId, copy.size(), copy, observedAt,
                watermark, request.freshnessMaxAgeSeconds(), EVIDENCE);
    }

    /** A complete answer whose claimed count deliberately disagrees with the set it returns. */
    public static Behaviour completeWithClaimedCount(
            String evidenceTenantId, List<String> members, long claimedCount, Instant observedAt,
            Instant watermark) {
        List<String> copy = List.copyOf(members);
        return request -> AudienceMaterializationResult.of(
                AudienceMaterializationOutcome.COMPLETE, evidenceTenantId, claimedCount, copy, observedAt,
                watermark, request.freshnessMaxAgeSeconds(), EVIDENCE);
    }

    /** A complete answer whose evidence belongs to another tenant. */
    public static Behaviour completeForTenant(
            String evidenceTenantId, List<String> members, Instant observedAt, Instant watermark) {
        return complete(evidenceTenantId, members, observedAt, watermark);
    }

    /** A complete answer whose freshness policy is not the one the consumer froze. */
    public static Behaviour completeUnderPolicy(
            String evidenceTenantId,
            List<String> members,
            Instant observedAt,
            Instant watermark,
            long freshnessMaxAgeSeconds) {
        List<String> copy = List.copyOf(members);
        return request -> AudienceMaterializationResult.of(
                AudienceMaterializationOutcome.COMPLETE, evidenceTenantId, copy.size(), copy, observedAt,
                watermark, freshnessMaxAgeSeconds, EVIDENCE);
    }

    /** A closed failure outcome that returns no member set at all. */
    public static Behaviour outcome(
            AudienceMaterializationOutcome outcome,
            String evidenceTenantId,
            long candidateCount,
            Instant observedAt,
            Instant watermark) {
        return request -> {
            AudienceMaterializationResult built = outcome.isComplete()
                    ? AudienceMaterializationResult.of(
                            outcome, evidenceTenantId, 0, List.of(), observedAt, watermark,
                            request.freshnessMaxAgeSeconds(), EVIDENCE)
                    : AudienceMaterializationResult.failed(
                            outcome, evidenceTenantId, observedAt, watermark,
                            request.freshnessMaxAgeSeconds(), EVIDENCE);
            return outcome == AudienceMaterializationOutcome.OVER_LIMIT
                    ? AudienceMaterializationResult.of(
                            outcome, evidenceTenantId, candidateCount, List.of(), observedAt, watermark,
                            request.freshnessMaxAgeSeconds(), EVIDENCE)
                    : built;
        };
    }

    /** A host that cannot answer at all. */
    public static Behaviour failing() {
        return request -> {
            throw new IllegalStateException("scripted audience host unavailable");
        };
    }

    /** Redacted diagnostics: the fixture holds no owner value. */
    @Override
    public String toString() {
        return "ScriptedAudiencePort[calls=" + calls + ']';
    }
}