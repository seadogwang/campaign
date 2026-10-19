package com.company.campaign.builder.compiler;

import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.builder.testsupport.FixtureCapability;
import com.company.campaign.builder.testsupport.RecordingAuthorizationService;
import com.company.campaign.security.authorization.AuthorizationDecision;
import com.company.campaign.security.authorization.AuthorizationDisposition;
import com.company.campaign.security.authorization.AuthorizationReason;
import com.company.campaign.security.authorization.DataScopeDecision;
import com.company.campaign.security.authorization.FailClosedAuthorizationService;
import com.company.campaign.security.authorization.ResourceKind;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.shared.error.ContractException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.company.campaign.builder.testsupport.BuilderFixtures.SEGMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Requirement 11: compile performs exactly one explicit Campaign Edit authorization for the server-resolved
 * Campaign or Create resource, and only a structurally exact ALLOWED decision publishes a Plan.
 */
class CampaignCompilerAuthorizationTest {

    private static final String VERSION = "segment-rule-based-1.0";

    private final FrozenAuthoringPolicy policy = BuilderFixtures.policy();
    private final FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
            FixtureCapability.passThrough(SEGMENT, VERSION, FixtureCapability.ordered("segment")));

    @Test
    void aCompileAuthorizesExactlyOnceForTheServerResolvedCampaign() {
        RecordingAuthorizationService authorization = new RecordingAuthorizationService();
        CampaignCompiler compiler = new CampaignCompiler(authorization);
        ResolvedComponent segment = BuilderFixtures.component(
                "segment-main", SEGMENT, VERSION, FixtureCapability.ordered("segment"), Map.of("spec", "a"));

        compiler.compile(CompileRequest.edit(editPatch(), BuilderFixtures.current(segment), catalog, policy,
                BuilderFixtures.instants(), BuilderFixtures.context()));

        assertThat(authorization.callCount()).isEqualTo(1);
        var request = authorization.requests().get(0);
        assertThat(request.resource().kind()).isEqualTo(ResourceKind.CAMPAIGN);
        assertThat(request.resource().resourceId()).isEqualTo(BuilderFixtures.CAMPAIGN);
        assertThat(request.resource().tenantId()).isEqualTo(BuilderFixtures.TENANT);
        assertThat(request.resource().programId()).isEqualTo(BuilderFixtures.PROGRAM);
        assertThat(request.context().tenantId()).isEqualTo(BuilderFixtures.TENANT);
    }

    @Test
    void aCreateAuthorizesTheServerResolvedCreateResource() {
        RecordingAuthorizationService authorization = new RecordingAuthorizationService();
        CampaignCompiler compiler = new CampaignCompiler(authorization);
        CampaignProposalPatch create = BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of()).build();

        compiler.compile(CompileRequest.create(
                create, catalog, policy, BuilderFixtures.instants(), BuilderFixtures.context()));

        assertThat(authorization.callCount()).isEqualTo(1);
        assertThat(authorization.requests().get(0).resource().kind()).isEqualTo(ResourceKind.CAMPAIGN);
        assertThat(authorization.requests().get(0).resource().resourceId())
                .isEqualTo(CampaignCompiler.CREATE_RESOURCE_ID);
    }

    @Test
    void everyNonExactDecisionFailsClosed() {
        List<AuthorizationDecision> unacceptable = List.of(
                AuthorizationDecision.deny(AuthorizationDisposition.PERMISSION_DENIED,
                        AuthorizationReason.MISSING_PERMISSION, ResourceKind.CAMPAIGN, CampaignPermission.CAMPAIGN_EDIT),
                AuthorizationDecision.deny(AuthorizationDisposition.NOT_FOUND,
                        AuthorizationReason.CROSS_TENANT_RESOURCE, ResourceKind.CAMPAIGN, CampaignPermission.CAMPAIGN_EDIT),
                AuthorizationDecision.deny(AuthorizationDisposition.DATASCOPE_DENIED,
                        AuthorizationReason.DATASCOPE_DENIED, ResourceKind.CAMPAIGN, CampaignPermission.CAMPAIGN_EDIT),
                AuthorizationDecision.deny(AuthorizationDisposition.DATASCOPE_UNAVAILABLE,
                        AuthorizationReason.DATASCOPE_UNAVAILABLE, ResourceKind.CAMPAIGN, CampaignPermission.CAMPAIGN_EDIT),
                AuthorizationDecision.deny(AuthorizationDisposition.INVALID_REQUEST,
                        AuthorizationReason.INVALID_REQUEST, ResourceKind.CAMPAIGN, CampaignPermission.CAMPAIGN_EDIT),
                // Structurally ALLOWED, but for another kind.
                AuthorizationDecision.allow(ResourceKind.SEGMENT, CampaignPermission.SEGMENT_EVALUATE),
                // Structurally ALLOWED, but the required permission is not the Campaign Edit permission.
                AuthorizationDecision.allow(ResourceKind.CAMPAIGN, CampaignPermission.CAMPAIGN_READ));

        for (AuthorizationDecision decision : unacceptable) {
            RecordingAuthorizationService authorization = new RecordingAuthorizationService(request -> decision);
            assertDenied(new CampaignCompiler(authorization), authorization);
        }
    }

    @Test
    void anAllowedDispositionWithTheWrongReasonFailsClosed() {
        RecordingAuthorizationService authorization = new RecordingAuthorizationService(request ->
                new AuthorizationDecision(
                        AuthorizationDisposition.ALLOWED,
                        AuthorizationReason.MISSING_PERMISSION,
                        ResourceKind.CAMPAIGN,
                        CampaignPermission.CAMPAIGN_EDIT));
        assertDenied(new CampaignCompiler(authorization), authorization);

        RecordingAuthorizationService nullDecision = new RecordingAuthorizationService(request -> null);
        assertDenied(new CampaignCompiler(nullDecision), nullDecision);

        RecordingAuthorizationService throwing = new RecordingAuthorizationService().failingWithException();
        assertDenied(new CampaignCompiler(throwing), throwing);
    }

    @Test
    void theAcceptedT02AuthorizationServiceAllowsCampaignEditAndDeniesWithoutIt() {
        FailClosedAuthorizationService permissive = new FailClosedAuthorizationService(
                query -> DataScopeDecision.allowed());
        RecordingAuthorizationService recording = new RecordingAuthorizationService();
        CampaignCompiler compiler = new CampaignCompiler(permissive);

        CampaignProposalPatch create = BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of()).build();
        CampaignPlan plan = compiler.compile(CompileRequest.create(
                create, catalog, policy, BuilderFixtures.instants(), BuilderFixtures.context()));
        assertThat(plan.manifest().components()).hasSize(1);

        // A context without campaign.edit is refused by the accepted fail-closed service itself.
        TrustedExecutionContext readOnly = BuilderFixtures.context(
                BuilderFixtures.TENANT, BuilderFixtures.PROGRAM, Set.of(CampaignPermission.CAMPAIGN_READ));
        ContractException failure = catchThrowableOfType(
                () -> compiler.compile(CompileRequest.create(
                        create, catalog, policy, BuilderFixtures.instants(), readOnly)),
                ContractException.class);
        assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_AUTHORIZATION_DENIED);
        assertThat(recording.callCount()).isZero();

        // And a DataScope policy that cannot decide also fails closed.
        FailClosedAuthorizationService unavailable = new FailClosedAuthorizationService(
                query -> DataScopeDecision.unavailable("DATASCOPE_BACKEND_DOWN"));
        CampaignCompiler closedCompiler = new CampaignCompiler(unavailable);
        ContractException unavailableFailure = catchThrowableOfType(
                () -> closedCompiler.compile(CompileRequest.create(
                        create, catalog, policy, BuilderFixtures.instants(), BuilderFixtures.context())),
                ContractException.class);
        assertThat(unavailableFailure.code()).isEqualTo(CompilerErrorCodes.COMPILER_AUTHORIZATION_DENIED);
    }

    @Test
    void theAuthorizationFailureNeverDisclosesTheResourceOrContext() {
        RecordingAuthorizationService authorization = new RecordingAuthorizationService(request ->
                AuthorizationDecision.deny(AuthorizationDisposition.NOT_FOUND,
                        AuthorizationReason.CROSS_TENANT_RESOURCE, ResourceKind.CAMPAIGN,
                        CampaignPermission.CAMPAIGN_EDIT));
        CampaignCompiler compiler = new CampaignCompiler(authorization);

        ContractException failure = catchThrowableOfType(
                () -> compiler.compile(CompileRequest.create(
                        patch(), catalog, policy, BuilderFixtures.instants(), BuilderFixtures.context())),
                ContractException.class);

        assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_AUTHORIZATION_DENIED);
        assertThat(failure.getMessage()).doesNotContain(
                BuilderFixtures.CAMPAIGN, BuilderFixtures.TENANT, BuilderFixtures.PROGRAM, BuilderFixtures.USER);
        assertThat(failure.details().toString()).doesNotContain(
                BuilderFixtures.CAMPAIGN, BuilderFixtures.TENANT, BuilderFixtures.PROGRAM, BuilderFixtures.USER);
    }

    private void assertDenied(CampaignCompiler compiler, RecordingAuthorizationService authorization) {
        ContractException failure = catchThrowableOfType(
                () -> compiler.compile(CompileRequest.create(
                        patch(), catalog, policy, BuilderFixtures.instants(), BuilderFixtures.context())),
                ContractException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_AUTHORIZATION_DENIED);
        assertThat(authorization.callCount()).isEqualTo(1);
    }

    private CampaignProposalPatch patch() {
        return BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of()).build();
    }

    private CampaignProposalPatch editPatch() {
        return BuilderFixtures.editProposal().pins(catalog, policy)
                .upsert(SEGMENT, "segment-main", Map.of("spec", "b")).build();
    }
}