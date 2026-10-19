package com.company.campaign.builder.agent;

import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.builder.segment.SegmentSchemaRepairPolicy;
import com.company.campaign.builder.skill.SegmentSkillDefinition;
import com.company.campaign.builder.tool.SegmentPreviewPort;
import com.company.campaign.builder.tool.SegmentToolCatalog;
import com.company.campaign.metadata.service.MetadataQueryService;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.context.TrustedContextProvider;
import com.company.campaign.segment.intent.RegisteredDefaultSource;
import com.company.campaign.segment.reference.AudienceReferenceDirectoryContext;
import com.company.campaign.segment.resolution.ResolutionPolicy;
import com.company.campaign.segment.validation.SegmentValidationPolicy;

import java.util.Objects;

/**
 * The immutable host-owned services of one formal Agent adapter.
 *
 * <p>Every trusted dependency is constructor-injected: the validated Skill and its closed Tool catalog, the
 * trusted-context provider, the exact permitted snapshot source, the authorized Metadata query service, the one
 * authorization boundary, the model port, the host-owned resolution policy and registered defaults, the
 * validation policy, the typed Safe Preview port and the optional audit sink and timing port. None of them is
 * derived from a model payload, so no request field can supply, override or weaken one.
 *
 * <p>The optional {@link AudienceReferenceDirectoryContext} is the same kind of host fact: when a host injects
 * one, an Intent that asks for an existing audience is resolved through it, and when none is injected the
 * accepted reference-free behavior is unchanged.
 * <p>There is deliberately no sample flag. The committed Skill declares
 * {@code sampleOutputPermitted=false}, so this Skill version always invokes the Safe Preview with
 * {@code includeSample=false}; a forged or custom environment cannot override the Skill, and a sample-enabled
 * Skill would be a separate versioned design rather than a boolean here.
 *
 * <p>The construction of this record is also the exact adapter compatibility gate: the same
 * {@link SegmentAgentSkillPreflight} proof is applied to any injected {@link SegmentSkillDefinition}, not only
 * to the committed validated resource, so a directly published definition cannot weaken the loader contract.
 */
public record SegmentAgentEnvironment(
        SegmentSkillDefinition skill,
        SegmentToolCatalog catalog,
        TrustedContextProvider trustedContextProvider,
        SegmentMetadataSnapshotSource snapshotSource,
        MetadataQueryService metadataQueryService,
        AuthorizationService authorizationService,
        SegmentIntentModelPort modelPort,
        ResolutionPolicy resolutionPolicy,
        RegisteredDefaultSource registeredDefaults,
        SegmentValidationPolicy validationPolicy,
        SegmentPreviewPort previewPort,
        SegmentAuditSink auditSink,
        SegmentRunTimings timings,
        SegmentSchemaRepairPolicy repairPolicy,
        SegmentStageObserver stageObserver,
        AudienceReferenceDirectoryContext referenceDirectory
) {

    public SegmentAgentEnvironment {
        skill = Objects.requireNonNull(skill, "skill");
        catalog = Objects.requireNonNull(catalog, "catalog");
        trustedContextProvider = Objects.requireNonNull(trustedContextProvider, "trustedContextProvider");
        snapshotSource = Objects.requireNonNull(snapshotSource, "snapshotSource");
        metadataQueryService = Objects.requireNonNull(metadataQueryService, "metadataQueryService");
        authorizationService = Objects.requireNonNull(authorizationService, "authorizationService");
        modelPort = Objects.requireNonNull(modelPort, "modelPort");
        resolutionPolicy = Objects.requireNonNull(resolutionPolicy, "resolutionPolicy");
        registeredDefaults = Objects.requireNonNull(registeredDefaults, "registeredDefaults");
        validationPolicy = Objects.requireNonNull(validationPolicy, "validationPolicy");
        previewPort = Objects.requireNonNull(previewPort, "previewPort");
        if (!catalog.skillHash().equals(skill.canonicalHash())
                || !catalog.skillId().equals(skill.skillId())
                || !catalog.skillVersion().equals(skill.skillVersion())) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID,
                    "A Tool catalog must be derived from the exact validated Skill");
        }
        SegmentAgentSkillPreflight.requireCompatible(skill, catalog);
        repairPolicy = repairPolicy == null ? SegmentSchemaRepairPolicy.DISABLED : repairPolicy;
        stageObserver = stageObserver == null ? SegmentStageObserver.NOOP : stageObserver;
    }

    /** Builds the environment and its closed Tool catalog from the exact validated Skill. */
    public static SegmentAgentEnvironment of(
            SegmentSkillDefinition skill,
            TrustedContextProvider trustedContextProvider,
            SegmentMetadataSnapshotSource snapshotSource,
            MetadataQueryService metadataQueryService,
            AuthorizationService authorizationService,
            SegmentIntentModelPort modelPort,
            ResolutionPolicy resolutionPolicy,
            RegisteredDefaultSource registeredDefaults,
            SegmentValidationPolicy validationPolicy,
            SegmentPreviewPort previewPort,
            SegmentAuditSink auditSink,
            SegmentRunTimings timings) {
        return new SegmentAgentEnvironment(
                skill, SegmentToolCatalog.fromSkill(skill), trustedContextProvider, snapshotSource,
                metadataQueryService, authorizationService, modelPort, resolutionPolicy, registeredDefaults,
                validationPolicy, previewPort, auditSink, timings, null, null, null);
    }

    /** A copy of this environment with one audit sink. */
    public SegmentAgentEnvironment withAuditSink(SegmentAuditSink sink) {
        return new SegmentAgentEnvironment(
                skill, catalog, trustedContextProvider, snapshotSource, metadataQueryService, authorizationService,
                modelPort, resolutionPolicy, registeredDefaults, validationPolicy, previewPort, sink, timings,
                repairPolicy, stageObserver, referenceDirectory);
    }

    /** A copy of this environment with one timing port. */
    public SegmentAgentEnvironment withTimings(SegmentRunTimings port) {
        return new SegmentAgentEnvironment(
                skill, catalog, trustedContextProvider, snapshotSource, metadataQueryService, authorizationService,
                modelPort, resolutionPolicy, registeredDefaults, validationPolicy, previewPort, auditSink, port,
                repairPolicy, stageObserver, referenceDirectory);
    }

    /** A copy of this environment with one host-owned validation policy. */
    public SegmentAgentEnvironment withValidationPolicy(
            com.company.campaign.segment.validation.SegmentValidationPolicy policy) {
        return new SegmentAgentEnvironment(
                skill, catalog, trustedContextProvider, snapshotSource, metadataQueryService, authorizationService,
                modelPort, resolutionPolicy, registeredDefaults, policy, previewPort, auditSink, timings,
                repairPolicy, stageObserver, referenceDirectory);
    }

    /** A copy of this environment with one injected typed Safe Preview port. */
    public SegmentAgentEnvironment withPreviewPort(SegmentPreviewPort port) {
        return new SegmentAgentEnvironment(
                skill, catalog, trustedContextProvider, snapshotSource, metadataQueryService, authorizationService,
                modelPort, resolutionPolicy, registeredDefaults, validationPolicy, port, auditSink, timings,
                repairPolicy, stageObserver, referenceDirectory);
    }

    /** A copy of this environment with one injected model port. */
    public SegmentAgentEnvironment withModelPort(SegmentIntentModelPort port) {
        return new SegmentAgentEnvironment(
                skill, catalog, trustedContextProvider, snapshotSource, metadataQueryService, authorizationService,
                port, resolutionPolicy, registeredDefaults, validationPolicy, previewPort, auditSink, timings,
                repairPolicy, stageObserver, referenceDirectory);
    }

    /**
     * A copy of this environment with one closed host-owned schema-repair policy.
     *
     * <p>{@link SegmentSchemaRepairPolicy#DISABLED} remains the default, so a host that does not opt in keeps
     * the accepted one-call behavior exactly.
     */
    public SegmentAgentEnvironment withRepairPolicy(SegmentSchemaRepairPolicy policy) {
        return new SegmentAgentEnvironment(
                skill, catalog, trustedContextProvider, snapshotSource, metadataQueryService, authorizationService,
                modelPort, resolutionPolicy, registeredDefaults, validationPolicy, previewPort, auditSink, timings,
                policy, stageObserver, referenceDirectory);
    }

    /**
     * A copy of this environment with one host-owned evaluation observation boundary.
     *
     * <p>The boundary is {@link SegmentStageObserver#NOOP} unless a host injects one; it cannot be named by a
     * model payload or an Agent envelope, and it cannot change any stage result.
     */
    public SegmentAgentEnvironment withStageObserver(SegmentStageObserver observer) {
        return new SegmentAgentEnvironment(
                skill, catalog, trustedContextProvider, snapshotSource, metadataQueryService, authorizationService,
                modelPort, resolutionPolicy, registeredDefaults, validationPolicy, previewPort, auditSink, timings,
                repairPolicy, observer, referenceDirectory);
    }

    /**
     * A copy of this environment with one host-owned Existing Audience Reference directory.
     *
     * <p>The directory is a <em>host</em> input exactly like the trusted-context provider or the snapshot
     * source: it is constructor-injected, is never read from a request payload or a model output, and a request
     * cannot name, replace or widen it. When none is injected the run keeps the accepted reference-free
     * behavior — an Intent that nevertheless asks for an existing audience fails closed with the T10
     * {@code REFERENCE_DIRECTORY_MISSING} code rather than degrading to a guess.
     */
    public SegmentAgentEnvironment withReferenceDirectory(AudienceReferenceDirectoryContext directory) {
        return new SegmentAgentEnvironment(
                skill, catalog, trustedContextProvider, snapshotSource, metadataQueryService, authorizationService,
                modelPort, resolutionPolicy, registeredDefaults, validationPolicy, previewPort, auditSink, timings,
                repairPolicy, stageObserver, directory);
    }
}