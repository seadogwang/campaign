package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.builder.agent.SegmentAgentEnvironment;
import com.company.campaign.builder.agent.SegmentStageObserver;
import com.company.campaign.builder.refine.SegmentRefinementEnvironment;
import com.company.campaign.builder.refine.SegmentRefinementPatch;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.builder.segment.SegmentSchemaRepairPolicy;
import com.company.campaign.builder.skill.SegmentSkillDefinition;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.segment.intent.RegisteredDefaultSource;
import com.company.campaign.segment.resolution.ResolutionPolicy;
import com.company.campaign.segment.validation.SegmentValidationPolicy;

/**
 * The host-owned services one frozen T13 run is executed against.
 *
 * <p>The runner never builds an Agent or refinement environment itself, because doing so would mean naming the
 * security, Metadata and Preview wiring that belongs to the host. It asks the host for the genuine, already
 * wired environment of one Case and injects only two things it owns: its observation boundary and its
 * pass-through recording provider decorator. That is what keeps T13 on the accepted pipeline rather than on a
 * parallel implementation.
 *
 * <p>Every method must return the exact pinned object for the given Case. A host that returns a different
 * snapshot, context or fixture service than the run contract pinned is refused as {@code PIN_STALE}.
 */
public interface EvaluationRunHost {

    /** The exact committed Skill every Case of this run executes. */
    SegmentSkillDefinition skill();

    /** The host-owned provider port of this run, before the runner wraps it for call-kind recording. */
    SegmentIntentModelPort modelPort();

    /** The closed host-owned schema-repair policy of this run. */
    SegmentSchemaRepairPolicy repairPolicy();

    /** The host-owned registered-default registry of this run. */
    RegisteredDefaultSource registeredDefaults();

    /** The host-owned resolution policy of this run. */
    ResolutionPolicy resolutionPolicy();

    /** The host-owned validation policy of this run. */
    SegmentValidationPolicy validationPolicy();

    /** The one authorization boundary the runner reuses for canonicalizing expected documents. */
    AuthorizationService authorizationService();

    /**
     * The genuine T11 Agent environment of one Case.
     *
     * @param pins the frozen Case pins
     * @param observer the runner-owned observation boundary to inject
     * @param modelPort the runner-owned recording decorator, to be used verbatim
     */
    SegmentAgentEnvironment agentEnvironment(
            EvaluationCasePins pins, SegmentStageObserver observer, SegmentIntentModelPort modelPort);

    /** The genuine T12 refinement environment of one refinement Case. */
    SegmentRefinementEnvironment refinementEnvironment(
            EvaluationCasePins pins, SegmentStageObserver observer);

    /** The trusted execution context of one Case, built from its frozen execution-context fixture. */
    TrustedExecutionContext trustedContext(EvaluationCasePins pins);

    /** The exact T09 services one fixture is executed against. */
    FixtureExecutionServices fixtureServices(String fixtureId);

    /** The host-pinned refinement proposal of one refinement Case; {@code null} for a create Case. */
    SegmentRefinementPatch refinementPatch(EvaluationCasePins pins);
}