package com.company.campaign.builder.agent;

/**
 * The optional host port that supplies a bounded run duration for audit evidence.
 *
 * <p>The adapter never reads a clock: a duration is either absent or supplied by the host, so a run's audit
 * projection cannot depend on the wall clock and stays byte-identical across replays.
 */
@FunctionalInterface
public interface SegmentRunTimings {

    /** The host-supplied duration in nanoseconds, or null when the host does not measure it. */
    Long durationNanos(String agentRunId);
}