package com.company.campaign.fullpublish;

import com.company.campaign.builder.capability.composition.T19PackFacts;
import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.capability.schedule.ScheduleBasicVersions;
import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CampaignPlanStore;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.evaluation.segment.gate.SegmentReadinessProjection;
import com.company.campaign.journey.compiler.JourneyCompilation;
import com.company.campaign.journey.graph.JourneyTopology;
import com.company.campaign.journey.compiler.JourneyCompileRequest;
import com.company.campaign.journey.compiler.JourneyCompiler;
import com.company.campaign.journey.compiler.JourneyManifest;
import com.company.campaign.lifecycle.CompatibilityDecision;
import com.company.campaign.lifecycle.CompatibilityOutcome;
import com.company.campaign.lifecycle.FrozenCompatibilityPort;
import com.company.campaign.lifecycle.FrozenCompatibilityQuery;
import com.company.campaign.lifecycle.LifecycleErrorCodes;
import com.company.campaign.lifecycle.LifecycleException;
import com.company.campaign.lifecycle.LifecycleStore;
import com.company.campaign.lifecycle.LifecycleSupport;
import com.company.campaign.lifecycle.ManifestValidationPort;
import com.company.campaign.lifecycle.PublishPreparation;
import com.company.campaign.lifecycle.PublishPreparationState;
import com.company.campaign.lifecycle.SavedCampaignDraft;
import com.company.campaign.lifecycle.SegmentActivationCheck;
import com.company.campaign.lifecycle.SegmentReadinessLookupPort;
import com.company.campaign.security.authorization.ResourceNotFoundException;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The one place both full-publication boundaries re-prove the accepted T17 evidence, re-compile the exact T20
 * Journey and re-verify the exact T21 Schedule.
 *
 * <p>Preparing is not authority to publish, so the full confirmation reaches the same decisions a second time
 * through exactly the same code: one helper, two callers, and no possibility of the two boundaries drifting
 * apart on ownership, DataScope, revision, expiry, compatibility, Manifest validation, Segment activation,
 * Journey compilation or Schedule compilation.
 */
final class FullPublicationRechecks {

    private FullPublicationRechecks() {
    }

    /** Re-proves the exact tenant, program and user ownership, non-disclosingly. */
    static void requireOwned(PublishPreparation preparation, TrustedExecutionContext context) {
        if (!preparation.tenantId().equals(context.tenantId())
                || !preparation.programId().equals(context.programId())
                || !preparation.userId().equals(context.userId())) {
            throw new ResourceNotFoundException();
        }
    }

    /** Re-proves the caller's DataScope is exactly the frozen one. */
    static void requireSameDataScope(PublishPreparation preparation, TrustedExecutionContext context) {
        if (!preparation.dataScopeId().equals(context.dataScopeId())) {
            throw stale("dataScope");
        }
    }

    /** Classifies the accepted preparation's durable state and refuses a consumed or expired one. */
    static void requirePreparedState(
            LifecycleStore baseStore, PublishPreparation preparation, Instant now) {
        PublishPreparationState state =
                preparation.durableState(now, baseStore.isPreparationConsumed(preparation.preparationId()));
        if (state == PublishPreparationState.CONSUMED) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_PREPARATION_CONSUMED,
                    "preparationConsumed",
                    "A preparation may be consumed by at most one successful publication.");
        }
        if (state == PublishPreparationState.EXPIRED) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_PREPARATION_EXPIRED,
                    "preparationExpiry",
                    "The preparation is at or past its expiry instant.");
        }
    }

    /** Re-loads the Campaign's saved Draft and re-proves it is exactly the state the preparation froze. */
    static SavedCampaignDraft requireCurrentDraft(LifecycleStore baseStore, PublishPreparation preparation) {
        SavedCampaignDraft draft = baseStore.findDraft(preparation.campaignId())
                .orElseThrow(() -> stale("savedDraft"));
        if (!draft.draftId().equals(preparation.draftId())
                || draft.draftRevision() != preparation.draftRevision()
                || draft.campaignRevision() != preparation.campaignRevision()
                || !draft.manifestHash().equals(preparation.manifestHash())
                || !draft.draftHash().equals(preparation.draftHash())
                || !Objects.equals(draft.forkBase(), preparation.forkBase())) {
            throw stale("draftRevision");
        }
        return draft;
    }

    /** Re-loads the frozen source Plan and re-proves the Draft still descends from it. */
    static CampaignPlan requireSourcePlan(
            CampaignPlanStore planStore, SavedCampaignDraft draft, PublishPreparation preparation) {
        CampaignPlan plan = planStore.find(draft.sourcePlanId()).orElseThrow(() -> stale("sourcePlan"));
        if (!plan.planId().equals(preparation.planId())
                || !plan.planHash().equals(preparation.planHash())
                || !plan.manifestHash().equals(preparation.manifestHash())) {
            throw stale("planBinding");
        }
        return plan;
    }

    /** Rechecks the frozen catalog, policy and capability pins against the host's current inputs. */
    static void requireCompatible(
            FrozenCompatibilityPort compatibilityPort, CampaignPlan plan, TrustedExecutionContext context) {
        FrozenCompatibilityQuery query = new FrozenCompatibilityQuery(
                plan.catalogVersion(), plan.catalogHash(), plan.policyVersion(), plan.policyHash(),
                plan.capabilityPins(), context);
        CompatibilityDecision decision;
        try {
            decision = compatibilityPort.recheck(query);
        } catch (RuntimeException exception) {
            throw stale("frozenCompatibility");
        }
        if (decision == null || decision.outcome() == null
                || decision.outcome() != CompatibilityOutcome.COMPATIBLE) {
            throw stale("frozenCompatibility");
        }
    }

    /** Re-runs the deterministic final-Manifest validation every production step shares. */
    static void requireValidated(
            ManifestValidationPort validationPort, CampaignPlan plan, TrustedExecutionContext context) {
        if (LifecycleSupport.validateFinalManifest(validationPort, plan.manifest(), context)
                != LifecycleSupport.ManifestValidationOutcome.VALID) {
            throw stale("finalManifest");
        }
    }

    /** Requires the exact latest production Segment activation, and that it is the evidence frozen. */
    static SegmentReadinessProjection requireActivation(
            SegmentReadinessLookupPort readinessPort,
            CampaignPlan plan,
            PublishPreparation preparation) {
        SegmentReadinessProjection readiness = SegmentActivationCheck.require(readinessPort, plan);
        ContentHash currentInputs = readiness.currentInputsHash();
        if (!readiness.capabilityId().equals(preparation.readinessCapability())
                || !readiness.state().name().equals(preparation.readinessState())
                || currentInputs == null
                || !currentInputs.equals(preparation.gateInputsHash())
                || !Objects.equals(readiness.approvedDecisionHash(), preparation.approvedDecisionHash())) {
            throw stale("activationDrift");
        }
        return readiness;
    }

    /** Resolves the frozen Journey topology and pack index of the exact saved Draft revision. */
    static JourneyPublishSource requireJourneySource(
            JourneyPublishSourcePort journeySourcePort, SavedCampaignDraft draft) {
        if (journeySourcePort == null) {
            throw journeyUnavailable("journeySourceWiring");
        }
        JourneyPublishSource source;
        try {
            source = journeySourcePort.currentSource(
                            draft.campaignId(), draft.draftId(), draft.draftRevision(), draft.manifestHash())
                    .orElse(null);
        } catch (RuntimeException exception) {
            throw journeyUnavailable("journeySource");
        }
        if (source == null) {
            throw journeyUnavailable("journeySourceAbsent");
        }
        return source;
    }

    /**
     * Re-compiles the exact T20 Journey from the current frozen Draft.
     *
     * <p>The topology frozen by the saved Draft's Manifest is authoritative: when the Draft carries one, the
     * host-resolved source must be exactly that topology, so a later, unrelated topology can never be
     * substituted into a publication. A Draft that carries none keeps the accepted host-resolved behaviour.
     */
    static JourneyManifest compileJourney(
            SavedCampaignDraft draft, PublishPreparation preparation, JourneyPublishSource source) {
        JourneyTopology frozen = draft.manifest().journeyTopology();
        if (frozen != null && !frozen.equals(source.topology())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_FULL_JOURNEY_MISMATCH,
                    "journeyTopologySubstitution",
                    "The resolved Journey topology is not the one the saved Draft froze.");
        }
        JourneyTopology topology = frozen == null ? source.topology() : frozen;
        JourneyCompilation compilation;
        try {
            compilation = new JourneyCompiler().compile(new JourneyCompileRequest(
                    draft.manifest(), topology, source.packIndex(),
                    FullPublishVersions.JOURNEY_COMPILER_VERSION,
                    FullPublishVersions.RUNTIME_SCHEMA_VERSION));
        } catch (LifecycleException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw journeyUnavailable("journeyCompile");
        }
        if (compilation == null || !compilation.isCompiled()) {
            throw journeyUnavailable("journeyCompile");
        }
        JourneyManifest journey = compilation.manifest();
        if (!journey.sourceManifestHash().equals(preparation.manifestHash())
                || !journey.sourceManifestHash().equals(draft.manifestHash())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_FULL_JOURNEY_MISMATCH,
                    "journeySourceManifest",
                    "The compiled Journey does not descend from the frozen Manifest.");
        }
        return journey;
    }

    /** Re-compiles the exact T21 Schedule from the single enabled {@code schedule.basic/1.0} component. */
    static CompiledSchedule compileSchedule(
            SavedCampaignDraft draft, JourneyManifest journey, JourneyPublishSource source) {
        List<ResolvedComponent> schedules = new ArrayList<>();
        for (ResolvedComponent component : draft.manifest().components()) {
            if (FullPublishVersions.SCHEDULE_CAPABILITY_CODE.equals(component.capabilityCode())) {
                schedules.add(component);
            }
        }
        if (schedules.size() != 1) {
            // Exactly one enabled schedule component is the frozen contract: none and two alike refuse, so a
            // publication can never be ambiguous about which schedule it freezes.
            throw scheduleUnavailable("scheduleComponentCardinality");
        }
        ResolvedComponent component = schedules.get(0);
        if (!FullPublishVersions.SCHEDULE_CONTRACT_VERSION.equals(component.contractVersion())
                || !ScheduleBasicVersions.SLOT.equals(component.slot())) {
            throw scheduleMismatch("scheduleComponentContract");
        }
        // The component must be enabled in the frozen index at exactly its own contract version, in the frozen
        // schedule slot, on a runtime this build can author or preview.
        T19PackFacts facts = factsOrNull(source.packIndex(), FullPublishVersions.SCHEDULE_CAPABILITY_CODE);
        if (facts == null
                || !facts.enabled()
                || !FullPublishVersions.SCHEDULE_CONTRACT_VERSION.equals(facts.contractVersion())
                || !component.slot().equals(facts.slot())
                || !source.packIndex().runtimeCompatible(facts)) {
            throw scheduleUnavailable("schedulePackEnablement");
        }
        try {
            return new ScheduleCompiler().compile(new ScheduleCompileRequest(
                    component.logicalId(), FullPublishVersions.SCHEDULE_CAPABILITY_CODE,
                    FullPublishVersions.SCHEDULE_CONTRACT_VERSION, component.value(),
                    draft.manifestHash(), journey.contentHash(), FullPublishVersions.SCHEDULE_FORMAT_VERSION,
                    FullPublishVersions.SCHEDULE_COMPILER_VERSION,
                    FullPublishVersions.RUNTIME_SCHEMA_VERSION));
        } catch (ScheduleException rejected) {
            // The compiler's own bounded code is preserved on the invariant, so a refusal stays diagnosable
            // without any payload value becoming observable.
            throw scheduleMismatch(rejected.code());
        } catch (RuntimeException rejected) {
            throw scheduleMismatch("scheduleCompile");
        }
    }

    static T19PackFacts factsOrNull(T19PackIndex index, String capabilityCode) {
        return index == null ? null : index.facts(capabilityCode).orElse(null);
    }

    static LifecycleException stale(String invariant) {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_FULL_PREPARATION_STALE,
                invariant,
                "The preparation's frozen evidence no longer matches the state it would publish.");
    }

    static LifecycleException journeyUnavailable(String invariant) {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_FULL_JOURNEY_UNAVAILABLE,
                invariant,
                "No trustworthy Journey source could be compiled for the frozen Draft.");
    }

    static LifecycleException scheduleUnavailable(String invariant) {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_FULL_SCHEDULE_UNAVAILABLE,
                invariant,
                "No single enabled schedule.basic/1.0 component was available.");
    }

    static LifecycleException scheduleMismatch(String invariant) {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_FULL_SCHEDULE_MISMATCH,
                invariant,
                "The Schedule does not match the frozen component it descends from.");
    }
}