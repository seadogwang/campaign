package com.company.campaign.segment.reference;

import com.company.campaign.security.authorization.AuthorizationDecision;
import com.company.campaign.security.authorization.AuthorizationDisposition;
import com.company.campaign.security.authorization.AuthorizationReason;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.authorization.ResourceKind;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.AUDIENCE_SCOPE;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.CALLER_SCOPE;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.DISJOINT_SCOPE;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.FOREIGN_TENANT;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.NARROW_SCOPE;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.SIBLING_PROGRAM;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.candidate;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.catalogWith;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.ref;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.referenceContext;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trusted reference graph resolver: exact pins, ownership, program policy, segment authorization, scope
 * intersection, cycles and the three configured bounds.
 *
 * <p>Each case asserts the exact stable finding code, because the codes — not the prose — are what the
 * validator, the API envelope and the later lifecycle tasks branch on.
 */
class ReferenceGraphResolverTest {

    private static final ReferenceGraphResolver RESOLVER = new ReferenceGraphResolver();

    private static ReferenceGraphOutcome resolve(
            List<ReferenceCandidate> candidates,
            InMemoryAudienceVersionCatalog catalog,
            TrustedExecutionContext context,
            AuthorizationService authorization) {
        return RESOLVER.resolve(candidates, context, authorization, referenceContext(catalog));
    }

    private static List<String> codes(ReferenceGraphOutcome outcome) {
        return outcome.issues().stream().map(ReferenceGraphIssue::code).toList();
    }

    // ---------------------------------------------------------------- the exact pin

    @Test
    void resolvesAnExactImmutablePinToItsFrozenCatalogFacts() {
        AudienceVersionRef vip = ref("audience-vip", "version-7");
        InMemoryAudienceVersionCatalog catalog = catalogWith(snapshot(vip));

        ReferenceGraphOutcome outcome = resolve(
                List.of(candidate("n-ref", vip)), catalog, SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll());

        assertThat(codes(outcome)).isEmpty();
        assertThat(outcome.graph().distinctVersions()).isEqualTo(1);
        assertThat(outcome.graph().maxDepth()).isEqualTo(1);
        ResolvedAudienceReference resolved = outcome.graph().versions().get(0);
        assertThat(resolved.reference()).isEqualTo(vip);
        assertThat(resolved.snapshot().canonicalAstHash()).isEqualTo(snapshot(vip).canonicalAstHash());
        assertThat(resolved.effectiveDataScope().dataScopeId()).isEqualTo(AUDIENCE_SCOPE);
    }

    @Test
    void theEffectiveScopeIsTheTrustedIntersectionAndNotTheCallerScopeArgument() {
        AudienceVersionRef vip = ref("audience-vip", "version-7");

        ReferenceGraphOutcome outcome = resolve(
                List.of(candidate("n-ref", vip)), catalogWith(snapshot(vip)),
                SegmentReferenceFixtures.context(), SegmentReferenceFixtures.allowAll());

        EffectiveDataScope caller = SegmentReferenceFixtures.dataScopePort()
                .callerScope(SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM,
                        "user-001", CALLER_SCOPE).orElseThrow();
        EffectiveDataScope referenced = SegmentReferenceFixtures.dataScopePort()
                .callerScope(SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM,
                        "user-001", AUDIENCE_SCOPE).orElseThrow();
        EffectiveDataScope effective = outcome.graph().versions().get(0).effectiveDataScope();

        // The referenced scope is a strict subset of the caller scope, so the trusted intersection is exactly
        // that subset: provably narrower than the caller's own effective scope, and never the caller's scope
        // handed back unchanged.
        assertThat(effective).isEqualTo(referenced);
        assertThat(effective.dataScopeId()).isNotEqualTo(caller.dataScopeId());
        assertThat(effective.dataScopeHash()).isNotEqualTo(caller.dataScopeHash());
    }

    // ---------------------------------------------------------------- deterministic failure

    @Test
    void anAbsentVersionAndACrossTenantVersionShareOneNonEnumeratingFinding() {
        AudienceVersionRef absent = ref("audience-missing", "version-1");
        AudienceVersionRef foreign = ref("audience-foreign", "version-1");
        InMemoryAudienceVersionCatalog catalog = catalogWith(
                snapshot(foreign, FOREIGN_TENANT, SegmentReferenceFixtures.PROGRAM,
                        com.company.campaign.metadata.domain.SubjectKind.MEMBER, AUDIENCE_SCOPE, List.of(), 1,
                        AudienceVersionAvailability.AVAILABLE));

        ReferenceGraphOutcome absentOutcome = resolve(
                List.of(candidate("n-a", absent)), catalog, SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll());
        ReferenceGraphOutcome foreignOutcome = resolve(
                List.of(candidate("n-b", foreign)), catalog, SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll());

        assertThat(codes(absentOutcome)).containsExactly(ReferenceErrorCodes.VERSION_NOT_FOUND);
        assertThat(codes(foreignOutcome)).containsExactly(ReferenceErrorCodes.VERSION_NOT_FOUND);
        // Nothing distinguishes them outward: same code, same message, no version or tenant identifier.
        assertThat(foreignOutcome.issues().get(0).message())
                .isEqualTo(absentOutcome.issues().get(0).message())
                .doesNotContain("audience-foreign", "tenant-999", FOREIGN_TENANT);
    }

    @Test
    void aSameIdWithChangedBytesIsAConflict() {
        AudienceVersionRef pinned = ref("audience-vip", "version-7");
        AudienceVersionRef changed = new AudienceVersionRef(
                pinned.audienceId(), pinned.versionId(), SegmentReferenceFixtures.hash("changed-bytes"));
        InMemoryAudienceVersionCatalog catalog = catalogWith(snapshot(changed));

        ReferenceGraphOutcome outcome = resolve(
                List.of(candidate("n-ref", pinned)), catalog, SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll());

        assertThat(codes(outcome)).containsExactly(ReferenceErrorCodes.VERSION_CONFLICT);
    }

    @Test
    void twoPinsWithOneIdentityButDifferentBytesNeverCollapseInEitherCandidateOrder() {
        AudienceVersionRef present = new AudienceVersionRef(
                "audience-vip", "version-7", SegmentReferenceFixtures.hash("bytes-one"));
        AudienceVersionRef contradicted = new AudienceVersionRef(
                "audience-vip", "version-7", SegmentReferenceFixtures.hash("bytes-two"));
        // The catalog knows this one version identity, so it can confirm exactly one of the two pins.
        InMemoryAudienceVersionCatalog catalog = catalogWith(snapshot(present));
        ReferenceValidationContext referenceContext = referenceContext(catalog);

        ReferenceGraphOutcome declared = RESOLVER.resolve(
                List.of(candidate("n-present", present), candidate("n-contradicted", contradicted)),
                SegmentReferenceFixtures.context(), SegmentReferenceFixtures.allowAll(), referenceContext);
        ReferenceGraphOutcome reordered = RESOLVER.resolve(
                List.of(candidate("n-contradicted", contradicted), candidate("n-present", present)),
                SegmentReferenceFixtures.context(), SegmentReferenceFixtures.allowAll(), referenceContext);

        // The pin the catalog could not confirm fails on its own terms, with the one non-disclosing conflict
        // finding, so no graph may be accepted for the document — in either declaration order.
        assertThat(codes(declared)).containsExactly(ReferenceErrorCodes.VERSION_CONFLICT);
        assertThat(declared.clean()).isFalse();
        assertThat(codes(reordered)).isEqualTo(codes(declared));
        assertThat(reordered.graph()).isEqualTo(declared.graph());
        assertThat(declared.graph().versions().stream().map(ResolvedAudienceReference::reference).toList())
                .containsExactly(present);
    }

    @Test
    void aLookupCacheNeverAnswersOnePinWithAnotherPinsSnapshot() {
        // Two exact pins of one version identity, each genuinely present in the catalog with its own bytes.
        AudienceVersionRef first = new AudienceVersionRef(
                "audience-vip", "version-7", SegmentReferenceFixtures.hash("bytes-one"));
        AudienceVersionRef second = new AudienceVersionRef(
                "audience-vip", "version-7", SegmentReferenceFixtures.hash("bytes-two"));
        Map<String, AudienceVersionSnapshot> byPin = Map.of(
                first.pinKey(), snapshot(first), second.pinKey(), snapshot(second));
        AtomicInteger inquiries = new AtomicInteger();
        AudienceVersionCatalog countingCatalog = reference -> {
            inquiries.incrementAndGet();
            return Optional.ofNullable(byPin.get(reference.pinKey()));
        };

        ReferenceGraphOutcome outcome = RESOLVER.resolve(
                List.of(candidate("n-1", first), candidate("n-2", first),
                        candidate("n-3", second), candidate("n-4", second)),
                SegmentReferenceFixtures.context(), SegmentReferenceFixtures.allowAll(),
                ReferenceValidationContext.of(countingCatalog, SegmentReferenceFixtures.sameProgramOnly(),
                        SegmentReferenceFixtures.dataScopePort(), ReferencePolicy.standard()));

        assertThat(codes(outcome)).isEmpty();
        assertThat(outcome.graph().distinctVersions()).isEqualTo(2);
        assertThat(outcome.graph().versions().stream().map(ResolvedAudienceReference::reference).toList())
                .containsExactlyInAnyOrder(first, second);
        // Each site resolved to the snapshot of its own pin, never to the other pin's cached answer.
        for (ResolvedAudienceReference resolved : outcome.graph().versions()) {
            assertThat(resolved.snapshot().version()).isEqualTo(resolved.reference());
        }
        // And each exact pin was asked for exactly once, however many sites named it.
        assertThat(inquiries.get()).isEqualTo(2);
    }

    @Test
    void aDeletedOrUnavailableVersionFailsDeterministically() {
        AudienceVersionRef deleted = ref("audience-vip", "version-7");
        AudienceVersionRef withdrawn = ref("audience-vip", "version-8");
        InMemoryAudienceVersionCatalog catalog = catalogWith(
                snapshot(deleted, SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM,
                        com.company.campaign.metadata.domain.SubjectKind.MEMBER, AUDIENCE_SCOPE, List.of(), 1,
                        AudienceVersionAvailability.DELETED),
                snapshot(withdrawn, SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM,
                        com.company.campaign.metadata.domain.SubjectKind.MEMBER, AUDIENCE_SCOPE, List.of(), 1,
                        AudienceVersionAvailability.UNAVAILABLE));

        assertThat(codes(resolve(List.of(candidate("n-d", deleted)), catalog,
                SegmentReferenceFixtures.context(), SegmentReferenceFixtures.allowAll())))
                .containsExactly(ReferenceErrorCodes.VERSION_UNAVAILABLE);
        assertThat(codes(resolve(List.of(candidate("n-w", withdrawn)), catalog,
                SegmentReferenceFixtures.context(), SegmentReferenceFixtures.allowAll())))
                .containsExactly(ReferenceErrorCodes.VERSION_UNAVAILABLE);
    }

    @Test
    void aThrowingCatalogOrAuthorizationBoundaryFailsClosed() {
        AudienceVersionRef vip = ref("audience-vip", "version-7");
        AudienceVersionCatalog throwingCatalog = reference -> {
            throw new IllegalStateException("catalog failure with secret-like text");
        };

        ReferenceGraphOutcome catalogOutcome = RESOLVER.resolve(
                List.of(candidate("n-ref", vip)), SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll(), referenceContext(catalogWith(snapshot(vip))));
        assertThat(codes(catalogOutcome)).isEmpty();

        ReferenceGraphOutcome brokenCatalog = RESOLVER.resolve(
                List.of(candidate("n-ref", vip)), SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll(),
                ReferenceValidationContext.of(throwingCatalog, SegmentReferenceFixtures.sameProgramOnly(),
                        SegmentReferenceFixtures.dataScopePort(), ReferencePolicy.standard()));
        assertThat(codes(brokenCatalog)).containsExactly(ReferenceErrorCodes.VERSION_NOT_FOUND);

        ReferenceGraphOutcome brokenAuthorization = resolve(
                List.of(candidate("n-ref", vip)), catalogWith(snapshot(vip)),
                SegmentReferenceFixtures.context(), SegmentReferenceFixtures.throwing());
        assertThat(codes(brokenAuthorization)).containsExactly(ReferenceErrorCodes.RESOURCE_NOT_ACCESSIBLE);
    }

    // ---------------------------------------------------------------- ownership and permission

    @Test
    void aDisallowedProgramRelationIsRefusedWithoutAClientFlag() {
        AudienceVersionRef sibling = ref("audience-sibling", "version-1");
        InMemoryAudienceVersionCatalog catalog = catalogWith(snapshot(
                sibling, SegmentReferenceFixtures.TENANT, SIBLING_PROGRAM,
                com.company.campaign.metadata.domain.SubjectKind.MEMBER, AUDIENCE_SCOPE, List.of(), 1,
                AudienceVersionAvailability.AVAILABLE));

        // The host policy does not permit reaching the sibling program.
        assertThat(codes(resolve(List.of(candidate("n-ref", sibling)), catalog,
                SegmentReferenceFixtures.context(), SegmentReferenceFixtures.allowAll())))
                .containsExactly(ReferenceErrorCodes.PROGRAM_NOT_ALLOWED);

        // The same version is reachable when the host policy allows the relation, and only then.
        ReferenceGraphOutcome allowed = RESOLVER.resolve(
                List.of(candidate("n-ref", sibling)), SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll(),
                referenceContext(catalog, SegmentReferenceFixtures.allowingSiblingProgram()));
        assertThat(codes(allowed)).isEmpty();
    }

    @Test
    void aSegmentAuthorizationDenialIsNonDisclosing() {
        AudienceVersionRef vip = ref("audience-vip", "version-7");

        for (AuthorizationService denial : List.of(
                SegmentReferenceFixtures.denyAll(), SegmentReferenceFixtures.denySegmentsOnly())) {
            ReferenceGraphOutcome outcome = resolve(
                    List.of(candidate("n-ref", vip)), catalogWith(snapshot(vip)),
                    SegmentReferenceFixtures.context(), denial);
            assertThat(codes(outcome)).containsExactly(ReferenceErrorCodes.RESOURCE_NOT_ACCESSIBLE);
            assertThat(outcome.issues().get(0).message())
                    .doesNotContain("audience-vip", "version-7", "segment.evaluate");
        }
    }

    @Test
    void aSegmentAuthorizationDecisionMustMatchTheRequestExactly() {
        AudienceVersionRef vip = ref("audience-vip", "version-7");
        InMemoryAudienceVersionCatalog catalog = catalogWith(snapshot(vip));

        List<AuthorizationDecision> unacceptable = new ArrayList<>();
        // A boundary that answers nothing at all.
        unacceptable.add(null);
        // Allowed dispositions carrying a reason that is not the allowed one.
        unacceptable.add(new AuthorizationDecision(AuthorizationDisposition.ALLOWED,
                AuthorizationReason.UNKNOWN_RESOURCE, ResourceKind.SEGMENT, CampaignPermission.SEGMENT_EVALUATE));
        unacceptable.add(new AuthorizationDecision(AuthorizationDisposition.ALLOWED,
                AuthorizationReason.CROSS_TENANT_RESOURCE, ResourceKind.SEGMENT,
                CampaignPermission.SEGMENT_EVALUATE));
        // Allowed decisions about another resource kind, or requiring a weaker permission than a segment does.
        unacceptable.add(AuthorizationDecision.allow(ResourceKind.ATTRIBUTE, CampaignPermission.CAMPAIGN_READ));
        unacceptable.add(AuthorizationDecision.allow(ResourceKind.PREVIEW_SAMPLE, CampaignPermission.AUDIENCE_PREVIEW));
        unacceptable.add(new AuthorizationDecision(AuthorizationDisposition.ALLOWED, AuthorizationReason.ALLOWED,
                ResourceKind.SEGMENT, CampaignPermission.CAMPAIGN_READ));
        // Allowed decisions that name no kind or no permission, so nothing about them is provable.
        unacceptable.add(new AuthorizationDecision(AuthorizationDisposition.ALLOWED, AuthorizationReason.ALLOWED,
                null, CampaignPermission.SEGMENT_EVALUATE));
        unacceptable.add(new AuthorizationDecision(AuthorizationDisposition.ALLOWED, AuthorizationReason.ALLOWED,
                ResourceKind.SEGMENT, null));
        unacceptable.add(new AuthorizationDecision(AuthorizationDisposition.ALLOWED, AuthorizationReason.ALLOWED,
                null, null));

        for (AuthorizationDecision decision : unacceptable) {
            ReferenceGraphOutcome outcome = resolve(
                    List.of(candidate("n-ref", vip)), catalog, SegmentReferenceFixtures.context(),
                    request -> decision);
            assertThat(codes(outcome)).containsExactly(ReferenceErrorCodes.RESOURCE_NOT_ACCESSIBLE);
            assertThat(outcome.issues().get(0).message())
                    .doesNotContain("audience-vip", "version-7", "segment.evaluate");
        }

        // Only the exact decision this reference asked for is evidence that the caller may reach the segment.
        ReferenceGraphOutcome accepted = resolve(
                List.of(candidate("n-ref", vip)), catalog, SegmentReferenceFixtures.context(),
                request -> AuthorizationDecision.allow(
                        request.resource().kind(), request.resource().kind().requiredPermission()));
        assertThat(codes(accepted)).isEmpty();
        assertThat(accepted.graph().distinctVersions()).isEqualTo(1);
    }

    @Test
    void aCallerWithoutTheSegmentPermissionIsDeniedByTheRealT02Boundary() {
        AudienceVersionRef vip = ref("audience-vip", "version-7");

        ReferenceGraphOutcome outcome = RESOLVER.resolve(
                List.of(candidate("n-ref", vip)),
                SegmentReferenceFixtures.contextWithoutSegmentEvaluate(),
                new com.company.campaign.security.authorization.FailClosedAuthorizationService(
                        query -> com.company.campaign.security.authorization.DataScopeDecision.allowed()),
                referenceContext(catalogWith(snapshot(vip))));

        assertThat(codes(outcome)).containsExactly(ReferenceErrorCodes.RESOURCE_NOT_ACCESSIBLE);
    }

    // ---------------------------------------------------------------- DataScope

    @Test
    void anEmptyScopeIntersectionFailsClosedAndIsNotTreatedAsNoMembers() {
        AudienceVersionRef disjoint = ref("audience-disjoint", "version-1");
        InMemoryAudienceVersionCatalog catalog = catalogWith(snapshot(
                disjoint, SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM,
                com.company.campaign.metadata.domain.SubjectKind.MEMBER, DISJOINT_SCOPE, List.of(), 1,
                AudienceVersionAvailability.AVAILABLE));

        assertThat(codes(resolve(List.of(candidate("n-ref", disjoint)), catalog,
                SegmentReferenceFixtures.context(), SegmentReferenceFixtures.allowAll())))
                .containsExactly(ReferenceErrorCodes.DATASCOPE_INTERSECTION_EMPTY);
    }

    @Test
    void anUnprovableCallerScopeIsUnavailableRatherThanPermissive() {
        AudienceVersionRef vip = ref("audience-vip", "version-7");
        InMemoryAudienceVersionCatalog catalog = catalogWith(snapshot(vip));
        // A port that cannot describe the caller's own scope at all.
        ReferenceDataScopePort unknownCaller = new InMemoryReferenceDataScopePort();

        ReferenceGraphOutcome outcome = RESOLVER.resolve(
                List.of(candidate("n-ref", vip)), SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll(),
                ReferenceValidationContext.of(catalog, SegmentReferenceFixtures.sameProgramOnly(),
                        unknownCaller, ReferencePolicy.standard()));

        assertThat(codes(outcome)).containsExactly(ReferenceErrorCodes.DATASCOPE_UNAVAILABLE);
    }

    @Test
    void aThrowingScopePortIsUnavailableNotAnException() {
        AudienceVersionRef vip = ref("audience-vip", "version-7");
        ReferenceDataScopePort throwing = new ReferenceDataScopePort() {
            @Override
            public java.util.Optional<EffectiveDataScope> callerScope(
                    String tenantId, String programId, String userId, String dataScopeId) {
                return java.util.Optional.of(SegmentReferenceFixtures.scope(CALLER_SCOPE));
            }

            @Override
            public java.util.Optional<EffectiveDataScope> intersect(
                    EffectiveDataScope caller, EffectiveDataScope parent, EffectiveDataScope referenced) {
                throw new IllegalStateException("scope service failure with secret-like text");
            }
        };

        ReferenceGraphOutcome outcome = RESOLVER.resolve(
                List.of(candidate("n-ref", vip)), SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll(),
                ReferenceValidationContext.of(catalogWith(snapshot(vip)),
                        SegmentReferenceFixtures.sameProgramOnly(), throwing, ReferencePolicy.standard()));

        assertThat(codes(outcome)).containsExactly(ReferenceErrorCodes.DATASCOPE_UNAVAILABLE);
    }

    @Test
    void aCallerScopePortThatDisagreesWithTheTrustedContextIsUnavailable() {
        AudienceVersionRef vip = ref("audience-vip", "version-7");
        ReferenceDataScopePort disagreeing = new InMemoryReferenceDataScopePort()
                .register(CALLER_SCOPE, java.util.Set.of("m1"))
                .register("scope-other", java.util.Set.of("m1"));

        ReferenceDataScopePort wrapped = new ReferenceDataScopePort() {
            @Override
            public java.util.Optional<EffectiveDataScope> callerScope(
                    String tenantId, String programId, String userId, String dataScopeId) {
                return disagreeing.callerScope(tenantId, programId, userId, "scope-other");
            }

            @Override
            public java.util.Optional<EffectiveDataScope> intersect(
                    EffectiveDataScope caller, EffectiveDataScope parent, EffectiveDataScope referenced) {
                return disagreeing.intersect(caller, parent, referenced);
            }
        };

        ReferenceGraphOutcome outcome = RESOLVER.resolve(
                List.of(candidate("n-ref", vip)), SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll(),
                ReferenceValidationContext.of(catalogWith(snapshot(vip)),
                        SegmentReferenceFixtures.sameProgramOnly(), wrapped, ReferencePolicy.standard()));

        assertThat(codes(outcome)).containsExactly(ReferenceErrorCodes.DATASCOPE_UNAVAILABLE);
    }

    @Test
    void aNestedReferenceNarrowsThroughTheParentEffectiveScope() {
        // parent is scoped to {m1,m2,m3}; child is scoped to {m1,m2}; caller is {m1..m4}, so the child's
        // effective scope is the true three-way intersection {m1,m2}.
        AudienceVersionRef parent = ref("audience-parent", "version-1");
        AudienceVersionRef child = ref("audience-child", "version-1");
        InMemoryAudienceVersionCatalog catalog = catalogWith(
                snapshot(parent, List.of(child)),
                snapshot(child, SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM,
                        com.company.campaign.metadata.domain.SubjectKind.MEMBER, NARROW_SCOPE, List.of(), 1,
                        AudienceVersionAvailability.AVAILABLE));

        ReferenceGraphOutcome outcome = resolve(
                List.of(candidate("n-ref", parent)), catalog, SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll());

        assertThat(codes(outcome)).isEmpty();
        assertThat(outcome.graph().distinctVersions()).isEqualTo(2);
        ResolvedAudienceReference childReference = outcome.graph().versions().stream()
                .filter(resolved -> resolved.reference().equals(child)).findFirst().orElseThrow();
        assertThat(childReference.depth()).isEqualTo(2);
        assertThat(childReference.effectiveDataScope().dataScopeId()).isEqualTo(NARROW_SCOPE);
    }

    // ---------------------------------------------------------------- subject scope

    @Test
    void aVersionDefinedOverADifferentSubjectIsRefusedAtItsSite() {
        AudienceVersionRef orderAudience = ref("audience-orders", "version-1");
        InMemoryAudienceVersionCatalog catalog = catalogWith(new AudienceVersionSnapshot(
                SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM, orderAudience,
                com.company.campaign.metadata.domain.SubjectKind.ORDER,
                SegmentReferenceFixtures.hash("ast"), SegmentReferenceFixtures.hash("plan"),
                SegmentReferenceFixtures.scope(AUDIENCE_SCOPE), List.of(),
                SegmentReferenceFixtures.hash("graph"),
                java.time.Instant.parse("2026-09-01T00:00:00Z"),
                AudienceVersionAvailability.AVAILABLE, 1));

        ReferenceGraphOutcome outcome = resolve(
                List.of(candidate("n-ref", orderAudience)), catalog, SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll());

        assertThat(codes(outcome)).containsExactly(ReferenceErrorCodes.SUBJECT_MISMATCH);
    }

    // ---------------------------------------------------------------- cycles

    @Test
    void rejectsASelfCycleATwoNodeCycleAndADeepCycle() {
        AudienceVersionRef a = ref("audience-a", "version-1");
        AudienceVersionRef b = ref("audience-b", "version-1");
        AudienceVersionRef c = ref("audience-c", "version-1");

        // A version that directly depends on itself cannot even be constructed as catalog material.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> snapshot(a, List.of(a))))
                .isInstanceOf(ReferenceException.class);

        // two-node: a -> b -> a
        InMemoryAudienceVersionCatalog twoNode = catalogWith(snapshot(a, List.of(b)), snapshot(b, List.of(a)));
        assertThat(codes(resolve(List.of(candidate("n-a", a)), twoNode,
                SegmentReferenceFixtures.context(), SegmentReferenceFixtures.allowAll())))
                .containsExactly(ReferenceErrorCodes.CYCLE_DETECTED);

        // deep: a -> b -> c -> a
        InMemoryAudienceVersionCatalog deep = catalogWith(
                snapshot(a, List.of(b)), snapshot(b, List.of(c)), snapshot(c, List.of(a)));
        assertThat(codes(resolve(List.of(candidate("n-a", a)), deep,
                SegmentReferenceFixtures.context(), SegmentReferenceFixtures.allowAll())))
                .containsExactly(ReferenceErrorCodes.CYCLE_DETECTED);
    }

    @Test
    void rejectsACandidateEditWhoseGraphReachesItsOwnRootVersion() {
        AudienceVersionRef root = ref("audience-root", "version-2");
        AudienceVersionRef other = ref("audience-other", "version-1");
        InMemoryAudienceVersionCatalog catalog = catalogWith(
                snapshot(other, List.of(root)), snapshot(root));

        ReferenceValidationContext context = new ReferenceValidationContext(
                catalog, SegmentReferenceFixtures.sameProgramOnly(), SegmentReferenceFixtures.dataScopePort(),
                ReferencePolicy.standard(), root);

        ReferenceGraphOutcome withoutRootIdentity = RESOLVER.resolve(
                List.of(candidate("n-ref", other)), SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll(),
                referenceContext(catalog));
        ReferenceGraphOutcome withRootIdentity = RESOLVER.resolve(
                List.of(candidate("n-ref", other)), SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll(), context);

        // Without the host telling us which version the document is about to become, the closure is acyclic.
        assertThat(codes(withoutRootIdentity)).isEmpty();
        // With it, the same graph reaches the version the edit is producing and is refused as a cycle.
        assertThat(codes(withRootIdentity)).containsExactly(ReferenceErrorCodes.CYCLE_DETECTED);
    }

    @Test
    void aReachableVersionIsNotACycleEvenWhenItAppearsOnTwoPaths() {
        // A diamond is not a cycle: a -> b, a -> c, b -> d, c -> d.
        AudienceVersionRef a = ref("audience-a", "version-1");
        AudienceVersionRef b = ref("audience-b", "version-1");
        AudienceVersionRef c = ref("audience-c", "version-1");
        AudienceVersionRef d = ref("audience-d", "version-1");
        InMemoryAudienceVersionCatalog catalog = catalogWith(
                snapshot(a, List.of(b, c)), snapshot(b, List.of(d)), snapshot(c, List.of(d)), snapshot(d));

        ReferenceGraphOutcome outcome = resolve(
                List.of(candidate("n-a", a)), catalog, SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll());

        assertThat(codes(outcome)).isEmpty();
        assertThat(outcome.graph().distinctVersions()).isEqualTo(4);
        assertThat(outcome.graph().maxDepth()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- bounds

    @Test
    void boundsTheTransitiveDepth() {
        AudienceVersionRef a = ref("audience-a", "version-1");
        AudienceVersionRef b = ref("audience-b", "version-1");
        AudienceVersionRef c = ref("audience-c", "version-1");
        AudienceVersionRef d = ref("audience-d", "version-1");
        InMemoryAudienceVersionCatalog catalog = catalogWith(
                snapshot(a, List.of(b)), snapshot(b, List.of(c)), snapshot(c, List.of(d)), snapshot(d));

        ReferenceGraphOutcome wide = RESOLVER.resolve(
                List.of(candidate("n-a", a)), SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll(),
                referenceContext(catalog, new ReferencePolicy(4, 32, 4096)));
        assertThat(codes(wide)).isEmpty();
        assertThat(wide.graph().maxDepth()).isEqualTo(4);

        ReferenceGraphOutcome shallow = RESOLVER.resolve(
                List.of(candidate("n-a", a)), SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll(),
                referenceContext(catalog, new ReferencePolicy(2, 32, 4096)));
        assertThat(codes(shallow)).containsExactly(ReferenceErrorCodes.DEPTH_EXCEEDED);
    }

    @Test
    void boundsTheDistinctVersionCount() {
        AudienceVersionRef root = ref("audience-root", "version-1");
        List<AudienceVersionRef> leaves = List.of(
                ref("audience-l1", "version-1"), ref("audience-l2", "version-1"), ref("audience-l3", "version-1"));
        InMemoryAudienceVersionCatalog catalog = new InMemoryAudienceVersionCatalog();
        catalog.publish(snapshot(root, leaves));
        leaves.forEach(leaf -> catalog.publish(snapshot(leaf)));

        ReferenceGraphOutcome allowed = RESOLVER.resolve(
                List.of(candidate("n-root", root)), SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll(),
                referenceContext(catalog, new ReferencePolicy(4, 4, 4096)));
        assertThat(codes(allowed)).isEmpty();
        assertThat(allowed.graph().distinctVersions()).isEqualTo(4);

        ReferenceGraphOutcome refused = RESOLVER.resolve(
                List.of(candidate("n-root", root)), SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll(),
                referenceContext(catalog, new ReferencePolicy(4, 2, 4096)));
        assertThat(codes(refused)).containsExactly(ReferenceErrorCodes.DISTINCT_VERSIONS_EXCEEDED);
    }

    @Test
    void boundsTheExpandedComplexity() {
        AudienceVersionRef heavy = ref("audience-heavy", "version-1");
        InMemoryAudienceVersionCatalog catalog = catalogWith(snapshot(
                heavy, SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM,
                com.company.campaign.metadata.domain.SubjectKind.MEMBER, AUDIENCE_SCOPE, List.of(), 900,
                AudienceVersionAvailability.AVAILABLE));

        assertThat(codes(RESOLVER.resolve(
                List.of(candidate("n-ref", heavy)), SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll(),
                referenceContext(catalog, new ReferencePolicy(4, 32, 1000))))).isEmpty();
        assertThat(codes(RESOLVER.resolve(
                List.of(candidate("n-ref", heavy)), SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll(),
                referenceContext(catalog, new ReferencePolicy(4, 32, 100)))))
                .containsExactly(ReferenceErrorCodes.EXPANDED_COMPLEXITY_EXCEEDED);
    }

    // ---------------------------------------------------------------- accounting

    @Test
    void duplicateSitesAreDeduplicatedForDependencyAccounting() {
        AudienceVersionRef vip = ref("audience-vip", "version-7");
        InMemoryAudienceVersionCatalog catalog = catalogWith(snapshot(vip));

        ReferenceGraphOutcome outcome = resolve(
                List.of(candidate("n-ref-1", vip), candidate("n-ref-2", vip), candidate("n-ref-3", vip)),
                catalog, SegmentReferenceFixtures.context(), SegmentReferenceFixtures.allowAll());

        assertThat(codes(outcome)).isEmpty();
        assertThat(outcome.graph().distinctVersions()).isEqualTo(1);
        assertThat(outcome.graph().versions()).hasSize(1);
    }

    @Test
    void aMissingVersionIsReportedOnceForItsDistinctPinAtItsFirstSite() {
        // Dependency accounting is by distinct pin, so an absent version named by three sites yields one
        // finding, bound to the first site in declaration order. That keeps the outward list bounded and
        // non-enumerating: a document cannot turn one invisible version into a fan-out of findings.
        AudienceVersionRef absent = ref("audience-missing", "version-1");
        InMemoryAudienceVersionCatalog catalog = catalogWith();

        ReferenceGraphOutcome outcome = resolve(
                List.of(candidate("n-a", absent), candidate("n-b", absent), candidate("n-c", absent)), catalog,
                SegmentReferenceFixtures.context(), SegmentReferenceFixtures.allowAll());

        assertThat(codes(outcome)).containsExactly(ReferenceErrorCodes.VERSION_NOT_FOUND);
        assertThat(outcome.issues().stream().map(ReferenceGraphIssue::nodeId)).containsExactly("n-a");
        // The decision does not depend on which site happened to be listed first in the call.
        ReferenceGraphOutcome reordered = resolve(
                List.of(candidate("n-c", absent), candidate("n-b", absent), candidate("n-a", absent)), catalog,
                SegmentReferenceFixtures.context(), SegmentReferenceFixtures.allowAll());
        assertThat(reordered.issues().stream().map(ReferenceGraphIssue::nodeId)).containsExactly("n-c");
    }

    @Test
    void resolutionIsDeterministicAcrossRepeatedRuns() {
        AudienceVersionRef a = ref("audience-a", "version-1");
        AudienceVersionRef b = ref("audience-b", "version-1");
        InMemoryAudienceVersionCatalog catalog = catalogWith(snapshot(a, List.of(b)), snapshot(b));

        ReferenceGraphOutcome first = resolve(
                List.of(candidate("n-b", b), candidate("n-a", a)), catalog,
                SegmentReferenceFixtures.context(), SegmentReferenceFixtures.allowAll());
        ReferenceGraphOutcome second = resolve(
                List.of(candidate("n-a", a), candidate("n-b", b)), catalog,
                SegmentReferenceFixtures.context(), SegmentReferenceFixtures.allowAll());

        assertThat(first.graph().graphHash()).isEqualTo(second.graph().graphHash());
        assertThat(first.graph().versions()).isEqualTo(second.graph().versions());
    }

    @Test
    void anEmptyCandidateListProducesTheOneStableEmptyGraph() {
        ReferenceGraphOutcome outcome = resolve(
                List.of(), catalogWith(), SegmentReferenceFixtures.context(), SegmentReferenceFixtures.allowAll());

        assertThat(codes(outcome)).isEmpty();
        assertThat(outcome.graph().isEmpty()).isTrue();
        assertThat(outcome.graph().graphHash()).isEqualTo(ReferenceGraph.of(List.of()).graphHash());
    }

    @Test
    void aNullInputIsRejectedRatherThanGuessed() {
        InMemoryAudienceVersionCatalog catalog = catalogWith();
        ReferenceValidationContext context = referenceContext(catalog);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> RESOLVER.resolve(
                null, SegmentReferenceFixtures.context(), SegmentReferenceFixtures.allowAll(), context)))
                .isInstanceOf(ReferenceException.class)
                .hasFieldOrPropertyWithValue("code", ReferenceErrorCodes.INPUT_INVALID);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> RESOLVER.resolve(
                List.of(), null, SegmentReferenceFixtures.allowAll(), context)))
                .isInstanceOf(ReferenceException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> RESOLVER.resolve(
                List.of(), SegmentReferenceFixtures.context(), SegmentReferenceFixtures.allowAll(), null)))
                .isInstanceOf(ReferenceException.class);
    }
}