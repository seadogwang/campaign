package com.company.campaign.lifecycle.testsupport;

import com.company.campaign.builder.compiler.CampaignCompiler;
import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CampaignPlanStore;
import com.company.campaign.builder.compiler.CompileInstants;
import com.company.campaign.builder.compiler.CompileRequest;
import com.company.campaign.builder.compiler.FrozenAuthoringPolicy;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.builder.compiler.InMemoryCampaignPlanStore;
import com.company.campaign.builder.compiler.TrustedCurrentCampaign;
import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.builder.testsupport.FixtureCapability;
import com.company.campaign.lifecycle.ApplyService;
import com.company.campaign.lifecycle.CompatibilityDecision;
import com.company.campaign.lifecycle.FrozenCompatibilityPort;
import com.company.campaign.lifecycle.InMemoryLifecycleStore;
import com.company.campaign.lifecycle.LifecycleIdGenerator;
import com.company.campaign.lifecycle.LifecycleStore;
import com.company.campaign.lifecycle.ManifestValidationPort;
import com.company.campaign.lifecycle.SavedCampaignDraft;
import com.company.campaign.lifecycle.SequentialLifecycleIdGenerator;
import com.company.campaign.contracts.Issue;
import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.MembershipStatus;
import com.company.campaign.security.context.PrincipalMembership;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.security.context.VerifiedIdentity;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Shared, deterministic fixtures for the T17 host-only lifecycle Apply tests.
 *
 * <p>Every Plan is a genuine T15 compiler product and every Draft used as an EDIT base is a genuine product
 * of a prior Apply, so the tests exercise the real boundary rather than a stand-in.
 */
public final class LifecycleTestFixtures {

    /** A bounded capability the frozen catalog declares; Apply does not gate on Segment readiness. */
    public static final String CAPABILITY = "message.template";

    /** The frozen contract version of {@link #CAPABILITY}. */
    public static final String CONTRACT_VERSION = "message-template-1.0";

    /** An instant strictly inside the compiled Plan's lifetime. */
    public static final Instant APPLIED_AT = Instant.parse("2026-10-03T00:05:00Z");

    /** A deterministic clock inside the compiled Plan's lifetime. */
    public static final Clock CLOCK = Clock.fixed(APPLIED_AT, ZoneOffset.UTC);

    public static final FrozenCapabilityCatalog CATALOG = BuilderFixtures.catalog(
            FixtureCapability.passThrough(
                    CAPABILITY, CONTRACT_VERSION, FixtureCapability.unorderedMulti("slot-message")));

    public static final FrozenAuthoringPolicy POLICY = BuilderFixtures.policy();

    private LifecycleTestFixtures() {
    }

    // ------------------------------------------------------------------ clocks and identities

    public static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    public static LifecycleIdGenerator ids() {
        return new SequentialLifecycleIdGenerator();
    }

    // ------------------------------------------------------------------ plans

    /** A CREATE Plan owned by the default trusted context. */
    public static CampaignPlan createPlan() {
        return createPlan(BuilderFixtures.context(), BuilderFixtures.instants());
    }

    /** A CREATE Plan owned by an explicit trusted context. */
    public static CampaignPlan createPlan(TrustedExecutionContext context) {
        return createPlan(context, BuilderFixtures.instants());
    }

    /** A CREATE Plan with explicit compile/expiry instants. */
    public static CampaignPlan createPlan(TrustedExecutionContext context, CompileInstants instants) {
        CampaignProposalPatch patch = BuilderFixtures.createProposal()
                .pins(CATALOG, POLICY)
                .upsertNew(CAPABILITY, "client-ref-1", BuilderFixtures.value("label", "welcome"))
                .build();
        return compile(CompileRequest.create(patch, CATALOG, POLICY, instants, context));
    }

    /**
     * An EDIT Plan compiled against an already-saved Draft.
     *
     * <p>The trusted Current campaign is derived from the Draft itself, so the Plan's base campaign/Draft
     * identity and revisions are exactly those of the Draft a prior Apply produced.
     */
    public static CampaignPlan editPlan(SavedCampaignDraft base, String label) {
        TrustedCurrentCampaign current = new TrustedCurrentCampaign(
                base.tenantId(), base.programId(), base.campaignId(), base.campaignRevision(),
                base.draftIdentity(), base.forkBase(), base.manifest().components());
        String existing = base.manifest().components().get(0).logicalId();
        CampaignProposalPatch patch = BuilderFixtures.editProposal()
                .pins(CATALOG, POLICY)
                .campaignId(base.campaignId())
                .campaignRevision(base.campaignRevision())
                .upsert(CAPABILITY, existing, BuilderFixtures.value("label", label))
                .build();
        return compile(CompileRequest.edit(
                patch, current, CATALOG, POLICY, BuilderFixtures.instants(),
                BuilderFixtures.context(base.tenantId(), base.programId())));
    }

    /**
     * A second, distinct EDIT Plan compiled against the same base Draft with different content, used to
     * prove that only the first of two competing writes wins and the second is refused as stale.
     */
    public static CampaignPlan competingEditPlan(SavedCampaignDraft base, String label) {
        return editPlan(base, label);
    }

    private static CampaignPlan compile(CompileRequest request) {
        return new CampaignCompiler(authorization()).compile(request);
    }

    // ------------------------------------------------------------------ ports

    public static InMemoryCampaignPlanStore planStore(CampaignPlan... plans) {
        InMemoryCampaignPlanStore store = new InMemoryCampaignPlanStore();
        for (CampaignPlan plan : plans) {
            store.store(plan);
        }
        return store;
    }

    public static AuthorizationService authorization() {
        return SegmentValidationFixtures.allowAll();
    }

    public static FrozenCompatibilityPort compatible() {
        return query -> CompatibilityDecision.compatible();
    }

    public static ManifestValidationPort valid() {
        return request -> List.of();
    }

    // ------------------------------------------------------------------ the service under test

    public static ApplyService service(CampaignPlanStore plans, LifecycleStore store) {
        return new ApplyService(
                plans, authorization(), compatible(), valid(), store, ids(), CLOCK);
    }

    public static ApplyService service(
            CampaignPlanStore plans,
            LifecycleStore store,
            FrozenCompatibilityPort compatibility,
            ManifestValidationPort validation,
            Clock clock) {
        return new ApplyService(
                plans, authorization(), compatibility, validation, store, ids(), clock);
    }

    public static InMemoryLifecycleStore store() {
        return new InMemoryLifecycleStore();
    }

    // ------------------------------------------------------------------ contexts

    /** The default context with every permission. */
    public static TrustedExecutionContext context() {
        return BuilderFixtures.context();
    }

    /** The default membership minus {@code campaign.edit}. */
    public static TrustedExecutionContext contextWithoutEdit() {
        Set<CampaignPermission> permissions = EnumSet.complementOf(EnumSet.of(CampaignPermission.CAMPAIGN_EDIT));
        return BuilderFixtures.context(BuilderFixtures.TENANT, BuilderFixtures.PROGRAM, permissions);
    }

    /** The default membership with only {@code campaign.edit} and no other permission. */
    public static TrustedExecutionContext contextEditOnly() {
        return BuilderFixtures.context(
                BuilderFixtures.TENANT, BuilderFixtures.PROGRAM,
                EnumSet.of(CampaignPermission.CAMPAIGN_EDIT));
    }

    /** A different verified user inside the same tenant and program. */
    public static TrustedExecutionContext otherUserContext() {
        return BuilderFixtures.contextWithUser("user-002");
    }

    /**
     * A different verified user inside the same tenant and program who also lacks {@code campaign.edit}.
     *
     * <p>It exists so a test can prove the boundary is non-enumerating for a caller the permission decision
     * alone would have refused: an absent Plan and a same-tenant Plan owned by another user must still be
     * indistinguishable.
     */
    public static TrustedExecutionContext otherUserWithoutEditContext() {
        return restrictedUserContext("user-002",
                EnumSet.complementOf(EnumSet.of(CampaignPermission.CAMPAIGN_EDIT)));
    }

    /**
     * A different verified user inside the same tenant and program who lacks {@code campaign.publish}.
     *
     * <p>The publish-phase analogue of {@link #otherUserWithoutEditContext()}.
     */
    public static TrustedExecutionContext otherUserWithoutPublishContext() {
        return restrictedUserContext("user-003",
                EnumSet.complementOf(EnumSet.of(CampaignPermission.CAMPAIGN_PUBLISH)));
    }

    private static TrustedExecutionContext restrictedUserContext(
            String user, Set<CampaignPermission> permissions) {
        Instant issuedAt = BuilderFixtures.COMPILED_AT;
        VerifiedIdentity identity = new VerifiedIdentity(user, "https://issuer.example", issuedAt);
        PrincipalMembership membership = new PrincipalMembership(
                "membership-" + user, user, BuilderFixtures.TENANT, BuilderFixtures.PROGRAM,
                BuilderFixtures.DATA_SCOPE, MembershipStatus.ACTIVE, permissions);
        TrustedExecutionContextRef reference = new TrustedExecutionContextRef(
                BuilderFixtures.TENANT, BuilderFixtures.PROGRAM, user, BuilderFixtures.DATA_SCOPE,
                "snapshot-" + user, new LocaleTag("zh-CN"), ZoneId.of("Asia/Shanghai"), "CNY",
                issuedAt, issuedAt.minusSeconds(60));
        return TrustedExecutionContext.of(identity, membership, reference);
    }

    /** A different tenant with every permission. */
    public static TrustedExecutionContext otherTenantContext() {
        return BuilderFixtures.context("tenant-999", BuilderFixtures.PROGRAM);
    }

    /** A different program inside the same tenant with every permission. */
    public static TrustedExecutionContext otherProgramContext() {
        return BuilderFixtures.context(BuilderFixtures.TENANT, "program-999");
    }

    /** A blocking validation issue. */
    public static Issue blockingIssue() {
        return Issue.error("FIXTURE_BLOCKING", "final manifest rejected");
    }
}