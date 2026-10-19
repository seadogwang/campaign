package com.company.campaign.segment.reference;

import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.AUDIENCE_SCOPE;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.CALLER_SCOPE;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.NARROW_SCOPE;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.candidate;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.catalogWith;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.ref;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every incoming graph path is validated, not just the first one that discovers a version.
 *
 * <p>A version reachable through more than one parent is a version with more than one set of facts about it:
 * each path carries its own parent subject and its own parent effective scope. A traversal that resolves a
 * version once and then skips it everywhere else silently accepts the second path's subject and hands back the
 * first path's — wider — scope. These cases pin the required behaviour down: the second path is proved, a
 * contradicting subject is a finding, a narrower path narrows the frozen scope, an empty path fails closed,
 * and the descendants of a narrowed version are revalidated under the narrower scope.
 */
class ReferenceGraphPathValidationTest {

    private static final ReferenceGraphResolver RESOLVER = new ReferenceGraphResolver();

    private static ReferenceGraphOutcome resolve(
            List<ReferenceCandidate> candidates,
            InMemoryAudienceVersionCatalog catalog,
            ReferenceValidationContext referenceContext) {
        return RESOLVER.resolve(candidates, SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll(), referenceContext);
    }

    private static ReferenceGraphOutcome resolve(
            List<ReferenceCandidate> candidates, InMemoryAudienceVersionCatalog catalog) {
        return resolve(candidates, catalog, SegmentReferenceFixtures.referenceContext(catalog));
    }

    private static List<String> codes(ReferenceGraphOutcome outcome) {
        return outcome.issues().stream().map(ReferenceGraphIssue::code).toList();
    }

    private static EffectiveDataScope effectiveScope(
            ReferenceGraphOutcome outcome, AudienceVersionRef reference) {
        return outcome.graph().versions().stream()
                .filter(resolved -> resolved.reference().equals(reference))
                .findFirst()
                .orElseThrow()
                .effectiveDataScope();
    }

    private static int depth(ReferenceGraphOutcome outcome, AudienceVersionRef reference) {
        return outcome.graph().versions().stream()
                .filter(resolved -> resolved.reference().equals(reference))
                .findFirst()
                .orElseThrow()
                .depth();
    }

    private static AudienceVersionSnapshot snapshotOver(
            AudienceVersionRef version, SubjectKind subject, String dataScopeId, List<AudienceVersionRef> deps) {
        return snapshot(version, SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM, subject,
                dataScopeId, deps, 1 + deps.size(), AudienceVersionAvailability.AVAILABLE);
    }

    // ---------------------------------------------------------------- the incoming parent subject

    @Test
    void aSharedTransitiveVersionIsJudgedAgainstEveryIncomingParentSubject() {
        // The shared version is reached twice: once under a MEMBER parent and once under an ORDER parent, and the
        // names put the matching parent first in canonical order, so a first-visit short circuit would resolve
        // the shared version there and never notice that the second path contradicts its subject.
        AudienceVersionRef root = ref("audience-root", "version-1");
        AudienceVersionRef memberParent = ref("audience-parent-a", "version-1");
        AudienceVersionRef orderParent = ref("audience-parent-z", "version-1");
        AudienceVersionRef shared = ref("audience-shared", "version-1");

        InMemoryAudienceVersionCatalog catalog = catalogWith(
                snapshot(root, List.of(memberParent, orderParent)),
                snapshot(memberParent, List.of(shared)),
                snapshotOver(orderParent, SubjectKind.ORDER, AUDIENCE_SCOPE, List.of(shared)),
                snapshot(shared));

        ReferenceGraphOutcome outcome = resolve(List.of(candidate("n-ref", root)), catalog);

        assertThat(codes(outcome)).containsExactly(ReferenceErrorCodes.SUBJECT_MISMATCH);
        assertThat(outcome.clean()).isFalse();
    }

    // ---------------------------------------------------------------- the incoming parent scope

    @Test
    void aSharedTransitiveVersionNarrowsToTheNarrowestPathThatReachesIt() {
        // Two parents of one shared version: the first is published under the audience scope {m1,m2,m3} and the
        // second under a strictly narrower one {m1,m2}. The frozen scope must be the narrower intersection, and
        // it must not depend on which site was declared first.
        AudienceVersionRef wideParent = ref("audience-parent-a", "version-1");
        AudienceVersionRef narrowParent = ref("audience-parent-z", "version-1");
        AudienceVersionRef shared = ref("audience-shared", "version-1");

        InMemoryAudienceVersionCatalog catalog = catalogWith(
                snapshot(wideParent, List.of(shared)),
                snapshotOver(narrowParent, SubjectKind.MEMBER, NARROW_SCOPE, List.of(shared)),
                snapshot(shared));

        ReferenceGraphOutcome declared = resolve(
                List.of(candidate("n-wide", wideParent), candidate("n-narrow", narrowParent)), catalog);
        ReferenceGraphOutcome reversed = resolve(
                List.of(candidate("n-narrow", narrowParent), candidate("n-wide", wideParent)), catalog);

        assertThat(codes(declared)).isEmpty();
        EffectiveDataScope narrow = SegmentReferenceFixtures.dataScopePort()
                .callerScope(SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM,
                        "user-001", NARROW_SCOPE)
                .orElseThrow();
        assertThat(effectiveScope(declared, shared)).isEqualTo(narrow);
        assertThat(effectiveScope(declared, shared).dataScopeId()).isEqualTo(NARROW_SCOPE);
        // Neither the frozen scope nor the graph material depends on the declaration order of the two sites.
        assertThat(effectiveScope(reversed, shared)).isEqualTo(effectiveScope(declared, shared));
        assertThat(reversed.graph().graphHash()).isEqualTo(declared.graph().graphHash());
    }

    @Test
    void aSharedTransitiveVersionWhoseSecondPathIsEmptyFailsClosed() {
        // The caller holds {m1..m4}. One parent reaches the shared version through {m1,m2,m3} and the other
        // through {m4}; the shared version's own scope is {m1}. The first path proves {m1}, the second proves
        // nothing at all — which is a refusal, not a silently reused wider scope.
        AudienceVersionRef wideParent = ref("audience-parent-a", "version-1");
        AudienceVersionRef m4Parent = ref("audience-parent-z", "version-1");
        AudienceVersionRef shared = ref("audience-shared", "version-1");

        InMemoryAudienceVersionCatalog catalog = catalogWith(
                snapshot(wideParent, List.of(shared)),
                snapshotOver(m4Parent, SubjectKind.MEMBER, "scope-m4", List.of(shared)),
                snapshotOver(shared, SubjectKind.MEMBER, "scope-m1", List.of()));

        ReferenceValidationContext referenceContext = ReferenceValidationContext.of(
                catalog, SegmentReferenceFixtures.sameProgramOnly(),
                new InMemoryReferenceDataScopePort()
                        .register(CALLER_SCOPE, Set.of("m1", "m2", "m3", "m4"))
                        .register(AUDIENCE_SCOPE, Set.of("m1", "m2", "m3"))
                        .register("scope-m4", Set.of("m4"))
                        .register("scope-m1", Set.of("m1")),
                ReferencePolicy.standard());

        ReferenceGraphOutcome declared = resolve(
                List.of(candidate("n-wide", wideParent), candidate("n-m4", m4Parent)), catalog, referenceContext);
        ReferenceGraphOutcome reversed = resolve(
                List.of(candidate("n-m4", m4Parent), candidate("n-wide", wideParent)), catalog, referenceContext);

        assertThat(codes(declared)).containsExactly(ReferenceErrorCodes.DATASCOPE_INTERSECTION_EMPTY);
        assertThat(declared.clean()).isFalse();
        assertThat(codes(reversed)).isEqualTo(codes(declared));
    }

    // ---------------------------------------------------------------- revalidation of descendants

    @Test
    void descendantsAreRevalidatedWhenALaterPathNarrowsTheirParent() {
        // The shared version is reached first through the wide parent, so its descendant is proved under the
        // wide scope. The second path narrows the shared version to {m1,m2}, and the descendant must be proved
        // again under that narrower scope rather than keeping the scope the first path produced.
        AudienceVersionRef wideParent = ref("audience-parent-a", "version-1");
        AudienceVersionRef narrowParent = ref("audience-parent-z", "version-1");
        AudienceVersionRef shared = ref("audience-shared", "version-1");
        AudienceVersionRef descendant = ref("audience-descendant", "version-1");

        InMemoryAudienceVersionCatalog catalog = catalogWith(
                snapshot(wideParent, List.of(shared)),
                snapshotOver(narrowParent, SubjectKind.MEMBER, NARROW_SCOPE, List.of(shared)),
                snapshot(shared, List.of(descendant)),
                snapshot(descendant));

        ReferenceGraphOutcome outcome = resolve(
                List.of(candidate("n-wide", wideParent), candidate("n-narrow", narrowParent)), catalog);

        assertThat(codes(outcome)).isEmpty();
        assertThat(effectiveScope(outcome, shared).dataScopeId()).isEqualTo(NARROW_SCOPE);
        assertThat(effectiveScope(outcome, descendant).dataScopeId()).isEqualTo(NARROW_SCOPE);
        // Discovery depth is accounting, not path validation: it stays at the first, deterministic discovery.
        assertThat(depth(outcome, descendant)).isEqualTo(3);
    }

    @Test
    void aGenuinelyNarrowedScopeStaysDescribableForItsDescendants() {
        // The caller holds {m1..m4}, the parent is published under {m1,m3} and the child under {m1,m2}. Their
        // intersection is {m1}, which no input names, so it is a scope only the trusted port can describe — and
        // the grandchild resolved under it must still succeed rather than failing closed as an empty one.
        AudienceVersionRef parent = ref("audience-parent", "version-1");
        AudienceVersionRef child = ref("audience-child", "version-1");
        AudienceVersionRef grandchild = ref("audience-grandchild", "version-1");

        InMemoryAudienceVersionCatalog catalog = catalogWith(
                snapshotOver(parent, SubjectKind.MEMBER, "scope-p13", List.of(child)),
                snapshotOver(child, SubjectKind.MEMBER, "scope-c12", List.of(grandchild)),
                snapshotOver(grandchild, SubjectKind.MEMBER, "scope-g1", List.of()));

        ReferenceValidationContext referenceContext = ReferenceValidationContext.of(
                catalog, SegmentReferenceFixtures.sameProgramOnly(),
                new InMemoryReferenceDataScopePort()
                        .register(CALLER_SCOPE, Set.of("m1", "m2", "m3", "m4"))
                        .register("scope-p13", Set.of("m1", "m3"))
                        .register("scope-c12", Set.of("m1", "m2"))
                        .register("scope-g1", Set.of("m1")),
                ReferencePolicy.standard());

        ReferenceGraphOutcome outcome = resolve(List.of(candidate("n-ref", parent)), catalog, referenceContext);

        assertThat(codes(outcome)).isEmpty();
        assertThat(effectiveScope(outcome, child).dataScopeId()).startsWith("ds-");
        assertThat(effectiveScope(outcome, child).dataScopeId()).isNotEqualTo("scope-p13");
        // The grandchild's own scope is exactly that derived intersection, so it is proven again under it
        // rather than failing closed against a scope the port could no longer describe.
        assertThat(effectiveScope(outcome, grandchild)).isEqualTo(effectiveScope(outcome, child));
    }

    // ---------------------------------------------------------------- accounting stays once per pin

    @Test
    void aPinReachedBySeveralPathsIsAccountedOnceAndStaysBounded() {
        AudienceVersionRef root = ref("audience-root", "version-1");
        AudienceVersionRef left = ref("audience-left", "version-1");
        AudienceVersionRef right = ref("audience-right", "version-1");
        AudienceVersionRef heavy = ref("audience-heavy", "version-1");

        InMemoryAudienceVersionCatalog catalog = catalogWith(
                snapshot(root, List.of(left, right)),
                snapshot(left, List.of(heavy)),
                snapshot(right, List.of(heavy)),
                snapshot(heavy, SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM,
                        SubjectKind.MEMBER, AUDIENCE_SCOPE, List.of(), 900,
                        AudienceVersionAvailability.AVAILABLE));

        ReferenceGraphOutcome outcome = resolve(List.of(candidate("n-root", root)), catalog);
        ReferenceGraphOutcome reordered = resolve(List.of(candidate("n-root", root)), catalog);

        assertThat(codes(outcome)).isEmpty();
        assertThat(outcome.graph().distinctVersions()).isEqualTo(4);
        assertThat(outcome.graph().maxDepth()).isEqualTo(3);
        // 3 (root) + 2 (left) + 2 (right) + 900 (heavy) — the shared version is counted exactly once.
        assertThat(outcome.graph().expandedComplexity()).isEqualTo(907);
        assertThat(reordered.graph().graphHash()).isEqualTo(outcome.graph().graphHash());

        ReferenceGraphOutcome refused = RESOLVER.resolve(
                List.of(candidate("n-root", root)), SegmentReferenceFixtures.context(),
                SegmentReferenceFixtures.allowAll(),
                SegmentReferenceFixtures.referenceContext(catalog, new ReferencePolicy(4, 32, 100)));
        assertThat(codes(refused)).containsExactly(ReferenceErrorCodes.EXPANDED_COMPLEXITY_EXCEEDED);
        assertThat(refused.graph().isEmpty()).isTrue();
    }

    @Test
    void cyclesStayBoundedWhenAVersionIsReachedThroughSeveralPaths() {
        AudienceVersionRef root = ref("audience-root", "version-1");
        AudienceVersionRef a = ref("audience-a", "version-1");
        AudienceVersionRef b = ref("audience-b", "version-1");

        InMemoryAudienceVersionCatalog catalog = catalogWith(
                snapshot(root, List.of(a, b)), snapshot(a, List.of(b)), snapshot(b, List.of(a)));

        ReferenceGraphOutcome outcome = resolve(List.of(candidate("n-root", root)), catalog);

        assertThat(codes(outcome)).containsExactly(ReferenceErrorCodes.CYCLE_DETECTED);
        assertThat(outcome.graph().maxDepth()).isLessThanOrEqualTo(ReferencePolicy.standard().maxDepth());
    }
}