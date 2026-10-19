package com.company.campaign.builder.refine;

import com.company.campaign.builder.agent.SegmentAgentEnvironment;
import com.company.campaign.builder.agent.SegmentAgentSkillPreflight;
import com.company.campaign.builder.agent.SegmentMetadataSnapshotSource;
import com.company.campaign.builder.agent.SegmentRunTimings;
import com.company.campaign.builder.agent.SegmentStageObserver;
import com.company.campaign.builder.skill.SegmentSkillDefinition;
import com.company.campaign.builder.tool.SegmentPreviewPort;
import com.company.campaign.builder.tool.SegmentToolCatalog;
import com.company.campaign.metadata.service.MetadataQueryService;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.context.TrustedContextProvider;
import com.company.campaign.segment.validation.SegmentValidationPolicy;

import java.util.Objects;

/**
 * The immutable host-owned services of one refinement orchestrator.
 *
 * <p>Everything trusted is constructor-injected: the exact validated T11 Skill and the closed Tool catalog
 * derived from it, the trusted-context provider, the exact permitted snapshot source, the authorized Metadata
 * query service, the one authorization boundary, the host-owned validation policy, the typed Safe Preview port
 * and the optional audit sink and timing port. None of them is derived from a patch, so no patch field can
 * supply, override or weaken one.
 *
 * <p>There is deliberately no model port, no resolution policy and no registered-default source: the refinement
 * flow resolves nothing and calls no model, so those dependencies are not representable here. There is also no
 * sample flag — the committed Skill declares {@code sampleOutputPermitted=false}, so the Safe Preview is always
 * invoked with {@code includeSample=false} and a sample-enabled refinement would be a separate versioned design
 * rather than a boolean here.
 *
 * <p>Construction is the exact compatibility gate. It re-proves the create-flow
 * {@link SegmentAgentSkillPreflight} proof for any injected {@link SegmentSkillDefinition} and additionally
 * proves the refinement path itself, so a custom definition cannot weaken the committed loader contract and a
 * Skill that cannot cover this flow is refused before a single protected stage runs.
 */
public record SegmentRefinementEnvironment(
        SegmentSkillDefinition skill,
        SegmentToolCatalog catalog,
        TrustedContextProvider trustedContextProvider,
        SegmentMetadataSnapshotSource snapshotSource,
        MetadataQueryService metadataQueryService,
        AuthorizationService authorizationService,
        SegmentValidationPolicy validationPolicy,
        SegmentPreviewPort previewPort,
        SegmentRefinementAuditSink auditSink,
        SegmentRunTimings timings,
        SegmentStageObserver stageObserver
) {

    public SegmentRefinementEnvironment {
        skill = Objects.requireNonNull(skill, "skill");
        catalog = Objects.requireNonNull(catalog, "catalog");
        trustedContextProvider = Objects.requireNonNull(trustedContextProvider, "trustedContextProvider");
        snapshotSource = Objects.requireNonNull(snapshotSource, "snapshotSource");
        metadataQueryService = Objects.requireNonNull(metadataQueryService, "metadataQueryService");
        authorizationService = Objects.requireNonNull(authorizationService, "authorizationService");
        validationPolicy = Objects.requireNonNull(validationPolicy, "validationPolicy");
        previewPort = Objects.requireNonNull(previewPort, "previewPort");
        if (!catalog.skillHash().equals(skill.canonicalHash())
                || !catalog.skillId().equals(skill.skillId())
                || !catalog.skillVersion().equals(skill.skillVersion())) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.SKILL_INCOMPATIBLE,
                    "A Tool catalog must be derived from the exact validated Skill");
        }
        SegmentRefinementSkillPreflight.requireCompatible(skill, catalog);
        stageObserver = stageObserver == null ? SegmentStageObserver.NOOP : stageObserver;
    }

    /** Builds the environment and its closed Tool catalog from the exact validated Skill. */
    public static SegmentRefinementEnvironment of(
            SegmentSkillDefinition skill,
            TrustedContextProvider trustedContextProvider,
            SegmentMetadataSnapshotSource snapshotSource,
            MetadataQueryService metadataQueryService,
            AuthorizationService authorizationService,
            SegmentValidationPolicy validationPolicy,
            SegmentPreviewPort previewPort,
            SegmentRefinementAuditSink auditSink,
            SegmentRunTimings timings) {
        return new SegmentRefinementEnvironment(
                skill, SegmentToolCatalog.fromSkill(skill), trustedContextProvider, snapshotSource,
                metadataQueryService, authorizationService, validationPolicy, previewPort, auditSink, timings,
                null);
    }

    /**
     * Reuses the exact Skill, catalog and host services of an accepted T11 Agent environment.
     *
     * <p>The refinement flow needs no model port, so the Agent environment's model port is deliberately not
     * carried over; every other dependency is the identical host-owned instance, and the T11 environment has
     * already proved the create-flow preflight for the same Skill.
     */
    public static SegmentRefinementEnvironment from(SegmentAgentEnvironment agent) {
        if (agent == null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.SKILL_INCOMPATIBLE,
                    "A refinement environment requires an accepted T11 Agent environment");
        }
        return new SegmentRefinementEnvironment(
                agent.skill(), agent.catalog(), agent.trustedContextProvider(), agent.snapshotSource(),
                agent.metadataQueryService(), agent.authorizationService(), agent.validationPolicy(),
                agent.previewPort(), null, agent.timings(), agent.stageObserver());
    }

    /** A copy of this environment with one audit sink. */
    public SegmentRefinementEnvironment withAuditSink(SegmentRefinementAuditSink sink) {
        return new SegmentRefinementEnvironment(
                skill, catalog, trustedContextProvider, snapshotSource, metadataQueryService, authorizationService,
                validationPolicy, previewPort, sink, timings, stageObserver);
    }

    /** A copy of this environment with one timing port. */
    public SegmentRefinementEnvironment withTimings(SegmentRunTimings port) {
        return new SegmentRefinementEnvironment(
                skill, catalog, trustedContextProvider, snapshotSource, metadataQueryService, authorizationService,
                validationPolicy, previewPort, auditSink, port, stageObserver);
    }

    /** A copy of this environment with one injected typed Safe Preview port. */
    public SegmentRefinementEnvironment withPreviewPort(SegmentPreviewPort port) {
        return new SegmentRefinementEnvironment(
                skill, catalog, trustedContextProvider, snapshotSource, metadataQueryService, authorizationService,
                validationPolicy, port, auditSink, timings, stageObserver);
    }

    /** A copy of this environment with one host-owned evaluation observation boundary. */
    public SegmentRefinementEnvironment withStageObserver(SegmentStageObserver observer) {
        return new SegmentRefinementEnvironment(
                skill, catalog, trustedContextProvider, snapshotSource, metadataQueryService, authorizationService,
                validationPolicy, previewPort, auditSink, timings, observer);
    }
}