package com.company.campaign.preview.adapter;

import com.company.campaign.query.fixture.FixtureDataset;
import com.company.campaign.query.fixture.FixtureException;
import com.company.campaign.query.fixture.FixtureExecutionResult;
import com.company.campaign.query.fixture.FixtureExecutor;
import com.company.campaign.query.fixture.FixtureMapping;
import com.company.campaign.query.fixture.FixtureScopeBinding;
import com.company.campaign.query.fixture.FxRateSnapshot;
import com.company.campaign.query.plan.ReferenceExecutionCheck;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.plan.TrustedPlanContext;
import com.company.campaign.preview.PreviewBackendException;
import com.company.campaign.preview.PreviewBackendIdentity;
import com.company.campaign.preview.PreviewBackendResult;
import com.company.campaign.preview.PreviewConditionStatistic;
import com.company.campaign.preview.PreviewExecutionBackend;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.segment.reference.AudienceMembershipPort;

import java.util.ArrayList;
import java.util.List;

/**
 * The T09C fixture adapter: a read-only {@link PreviewExecutionBackend} over the accepted T09A deterministic
 * fixture executor.
 *
 * <p>Its constructor receives exact server-owned {@link FixtureDataset}, {@link FixtureMapping},
 * {@link FixtureScopeBinding} and, when a conversion is required, an immutable {@link FxRateSnapshot}. It
 * never loads a path supplied by a request and it exposes no fixture identity, payload row, dataset hash or
 * file path in the translated result — only the execution facts the Preview port requires.
 *
 * <p>It re-proves that the genuine plan's trusted context matches the caller's trusted context before
 * delegating, and it re-proves every pin the fixture result echoes against the plan and context before
 * translating. A mismatch or a fixture failure becomes one bounded {@link PreviewBackendException}; no
 * fixture identifier, member identifier, payload row or thrown message escapes.
 *
 * <p>There is deliberately no production fixture default bean: a host must construct this adapter with
 * explicit server-owned fixtures, so the normal application cannot start a usable Preview endpoint.
 */
public final class FixturePreviewExecutionBackend implements PreviewExecutionBackend {

    /** The stable server-owned identity of this adapter, bound into every result it publishes. */
    public static final String BACKEND_ID = "preview-fixture-backend";

    private static final FixtureExecutor EXECUTOR = new FixtureExecutor();

    private final FixtureDataset dataset;
    private final FixtureMapping mapping;
    private final FixtureScopeBinding scopeBinding;
    private final FxRateSnapshot fxRateSnapshot;
    private final AudienceMembershipPort audienceMembership;
    private final ReferenceExecutionCheck referenceExecutionCheck;
    private final PreviewBackendIdentity identity;

    /**
     * Creates the adapter from explicit server-owned fixture inputs.
     *
     * <p>A null dataset, mapping or scope binding, a null/blank backend version and a malformed identity all
     * fail with one bounded, non-disclosing {@link PreviewBackendException} — never a raw
     * {@link NullPointerException} or a payload-bearing {@link IllegalArgumentException}. The FX snapshot is
     * the one deliberately optional input: a conversion plan without it fails at execution time, never at
     * construction. The supplied values are never echoed in the failure.
     *
     * <p>This reference-free form refuses a plan that freezes an Existing Audience Reference: there is no
     * membership dataset to answer for the exact pin and no execution guard to re-prove it, so preview
     * generation cannot narrow the check by construction. Use
     * {@link #FixturePreviewExecutionBackend(FixtureDataset, FixtureMapping, FixtureScopeBinding,
     * FxRateSnapshot, String, AudienceMembershipPort, ReferenceExecutionCheck)} for a reference-bearing plan.
     */
    public FixturePreviewExecutionBackend(
            FixtureDataset dataset,
            FixtureMapping mapping,
            FixtureScopeBinding scopeBinding,
            FxRateSnapshot fxRateSnapshot,
            String backendVersion) {
        this(dataset, mapping, scopeBinding, fxRateSnapshot, backendVersion, null, null);
    }

    /**
     * Creates the adapter with the exact-pin membership dataset and execution check a reference needs.
     *
     * <p>Both extra inputs are host-owned and mandatory together: an adapter that could answer for a
     * reference but not re-prove it, or re-prove it but not resolve it, is not representable.
     */
    public FixturePreviewExecutionBackend(
            FixtureDataset dataset,
            FixtureMapping mapping,
            FixtureScopeBinding scopeBinding,
            FxRateSnapshot fxRateSnapshot,
            String backendVersion,
            AudienceMembershipPort audienceMembership,
            ReferenceExecutionCheck referenceExecutionCheck) {
        this.dataset = requireFixtureInput(dataset);
        this.mapping = requireFixtureInput(mapping);
        this.scopeBinding = requireFixtureInput(scopeBinding);
        this.fxRateSnapshot = fxRateSnapshot;
        this.audienceMembership = audienceMembership;
        this.referenceExecutionCheck = referenceExecutionCheck;
        this.identity = requireIdentity(backendVersion);
    }

    private static <T> T requireFixtureInput(T value) {
        if (value == null) {
            throw new PreviewBackendException(PreviewBackendException.Kind.INVALID_RESULT);
        }
        return value;
    }

    private static PreviewBackendIdentity requireIdentity(String backendVersion) {
        if (backendVersion == null || backendVersion.isBlank()) {
            throw new PreviewBackendException(PreviewBackendException.Kind.INVALID_RESULT);
        }
        try {
            return new PreviewBackendIdentity(BACKEND_ID, backendVersion);
        } catch (RuntimeException exception) {
            // The stable identity contract is enforced through one bounded backend outcome, never a raw cause.
            throw new PreviewBackendException(PreviewBackendException.Kind.INVALID_RESULT);
        }
    }

    @Override
    public PreviewBackendIdentity identity() {
        return identity;
    }

    @Override
    public PreviewBackendResult execute(SemanticQueryPlan plan, TrustedExecutionContext context) {
        if (plan == null || context == null) {
            throw new PreviewBackendException(PreviewBackendException.Kind.INVALID_RESULT);
        }
        requireContextMatchesPlan(plan.trustedContext(), context);

        // The execution guard runs first, before a single member is evaluated: a withdrawn version, a lost
        // SEGMENT permission, a released retention hold or a moved effective DataScope refuses preview
        // outright rather than previewing a stale answer. A plan with no reference consults nothing here.
        if (plan.hasAudienceReferences()) {
            if (referenceExecutionCheck == null || audienceMembership == null) {
                throw new PreviewBackendException(PreviewBackendException.Kind.INVALID_RESULT);
            }
            try {
                referenceExecutionCheck.requireExecutable(plan);
            } catch (RuntimeException exception) {
                throw new PreviewBackendException(PreviewBackendException.Kind.INVALID_RESULT);
            }
        }

        FixtureExecutionResult result;
        try {
            result = EXECUTOR.execute(plan, dataset, mapping, scopeBinding, fxRateSnapshot, audienceMembership);
        } catch (FixtureException exception) {
            // A fixture contract failure is an unusable result, never a client-visible fixture diagnostic.
            throw new PreviewBackendException(PreviewBackendException.Kind.INVALID_RESULT);
        } catch (RuntimeException exception) {
            throw new PreviewBackendException(PreviewBackendException.Kind.INVALID_RESULT);
        }
        requireResultPins(result, plan, context);

        return PreviewBackendResult.publish(
                result.memberIdSet(),
                statistics(result),
                result.planHash(),
                result.metadataSnapshotId(),
                result.dataScopeId(),
                result.asOf(),
                result.timezone(),
                result.dataWatermark(),
                identity.id(),
                identity.version());
    }

    /**
     * The plan's trusted context must equal the caller's trusted context on every execution-relevant fact.
     */
    private static void requireContextMatchesPlan(TrustedPlanContext planContext, TrustedExecutionContext context) {
        if (planContext == null || context.snapshotRef() == null) {
            throw new PreviewBackendException(PreviewBackendException.Kind.INVALID_RESULT);
        }
        if (!planContext.tenantId().equals(context.tenantId())
                || !planContext.programId().equals(context.programId())
                || !planContext.dataScopeId().equals(context.dataScopeId())
                || !planContext.asOf().equals(context.snapshotRef().asOf())
                || !planContext.dataWatermark().equals(context.snapshotRef().dataWatermark())
                || !planContext.timezone().equals(context.snapshotRef().timezone().getId())) {
            throw new PreviewBackendException(PreviewBackendException.Kind.INVALID_RESULT);
        }
    }

    /** Re-proves every pin the fixture result forwards against the genuine plan and trusted context. */
    private static void requireResultPins(
            FixtureExecutionResult result, SemanticQueryPlan plan, TrustedExecutionContext context) {
        if (!result.planHash().equals(plan.planHash())
                || !result.metadataSnapshotId().equals(plan.metadataSnapshotId())
                || !result.metadataSnapshotId().equals(context.snapshotRef().metadataSnapshotId())
                || !result.dataScopeId().equals(plan.dataScopePredicate().dataScopeId())
                // A reference-free plan must still report exactly the caller's scope; a reference-bearing plan
                // reports the effective intersection, which is deliberately narrower than the caller's own.
                || (!plan.dataScopePredicate().isEffectiveIntersection()
                        && !result.dataScopeId().equals(context.dataScopeId()))
                || !result.asOf().equals(context.snapshotRef().asOf())
                || !result.timezone().equals(context.snapshotRef().timezone().getId())
                || !result.dataWatermark().equals(context.snapshotRef().dataWatermark())) {
            throw new PreviewBackendException(PreviewBackendException.Kind.INVALID_RESULT);
        }
    }

    /** Translates the fixture statistics to the Preview-owned bounded shape, preserving structural paths. */
    private static List<PreviewConditionStatistic> statistics(FixtureExecutionResult result) {
        List<FixtureExecutionResult.PathStatistic> source = result.conditionStatistics();
        List<PreviewConditionStatistic> translated = new ArrayList<>(source.size());
        for (FixtureExecutionResult.PathStatistic statistic : source) {
            translated.add(new PreviewConditionStatistic(
                    statistic.path(), statistic.trueCount(), statistic.falseCount(), statistic.unknownCount()));
        }
        return translated;
    }
}