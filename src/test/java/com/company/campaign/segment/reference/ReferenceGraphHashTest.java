package com.company.campaign.segment.reference;

import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.AUDIENCE_SCOPE;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.candidate;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.catalogWith;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.hash;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.ref;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic dependency graph hash.</p>
 *
 * <p>It must move for every change to an executable fact and stay put for every change that is only about how
 * the document happens to be written down — site order, repeated sites, node names or tree shape.
 */
class ReferenceGraphHashTest {

    private static final ReferenceGraphResolver RESOLVER = new ReferenceGraphResolver();

    private static ReferenceGraph graph(List<ReferenceCandidate> candidates, InMemoryAudienceVersionCatalog catalog) {
        ReferenceGraphOutcome outcome = RESOLVER.resolve(
                candidates, SegmentReferenceFixtures.context(), SegmentReferenceFixtures.allowAll(),
                SegmentReferenceFixtures.referenceContext(catalog));
        assertThat(outcome.clean()).isTrue();
        return outcome.graph();
    }

    @Test
    void theSameSemanticGraphHashesIdenticallyHoweverItIsWrittenDown() {
        AudienceVersionRef a = ref("audience-a", "version-1");
        AudienceVersionRef b = ref("audience-b", "version-1");
        InMemoryAudienceVersionCatalog catalog = catalogWith(snapshot(a, List.of(b)), snapshot(b));

        ReferenceGraph declaredOrder = graph(List.of(candidate("n-1", a), candidate("n-2", b)), catalog);
        ReferenceGraph reversed = graph(List.of(candidate("n-2", b), candidate("n-1", a)), catalog);
        ReferenceGraph renamed = graph(List.of(candidate("z", a), candidate("y", b)), catalog);
        ReferenceGraph duplicated = graph(
                List.of(candidate("n-1", a), candidate("n-2", b), candidate("n-3", b)), catalog);

        assertThat(reversed.graphHash()).isEqualTo(declaredOrder.graphHash());
        assertThat(renamed.graphHash()).isEqualTo(declaredOrder.graphHash());
        assertThat(duplicated.graphHash()).isEqualTo(declaredOrder.graphHash());
        assertThat(duplicated.distinctVersions()).isEqualTo(2);
    }

    @Test
    void everyExecutableFactMovesTheHash() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        AudienceVersionRef dependency = ref("audience-dep", "version-1");
        AudienceVersionSnapshot baseline = snapshot(version);

        ReferenceGraph base = graph(List.of(candidate("n", version)), catalogWith(baseline));

        assertThat(graph(List.of(candidate("n", ref("audience-vip", "version-8"))),
                catalogWith(snapshot(ref("audience-vip", "version-8")))).graphHash())
                .isNotEqualTo(base.graphHash());
        assertThat(graph(List.of(candidate("n", version)), catalogWith(new AudienceVersionSnapshot(
                SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM, version, SubjectKind.MEMBER,
                hash("other-ast"), baseline.semanticQueryPlanHash(), baseline.effectiveDataScope(),
                List.of(), baseline.dependencyGraphHash(), baseline.publishedAt(),
                AudienceVersionAvailability.AVAILABLE, 1))).graphHash())
                .isNotEqualTo(base.graphHash());
        assertThat(graph(List.of(candidate("n", version)), catalogWith(new AudienceVersionSnapshot(
                SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM, version, SubjectKind.MEMBER,
                baseline.canonicalAstHash(), hash("other-plan"), baseline.effectiveDataScope(),
                List.of(), baseline.dependencyGraphHash(), baseline.publishedAt(),
                AudienceVersionAvailability.AVAILABLE, 1))).graphHash())
                .isNotEqualTo(base.graphHash());
        assertThat(graph(List.of(candidate("n", version)), catalogWith(new AudienceVersionSnapshot(
                SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM, version, SubjectKind.MEMBER,
                baseline.canonicalAstHash(), baseline.semanticQueryPlanHash(),
                SegmentReferenceFixtures.scope(AUDIENCE_SCOPE), List.of(), hash("other-graph"),
                baseline.publishedAt(), AudienceVersionAvailability.AVAILABLE, 1))).graphHash())
                .isNotEqualTo(base.graphHash());
        assertThat(graph(List.of(candidate("n", version)), catalogWith(
                new AudienceVersionSnapshot(
                        SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM, version,
                        SubjectKind.MEMBER, baseline.canonicalAstHash(), baseline.semanticQueryPlanHash(),
                        baseline.effectiveDataScope(), List.of(dependency), baseline.dependencyGraphHash(),
                        baseline.publishedAt(), AudienceVersionAvailability.AVAILABLE, 2),
                snapshot(dependency))).graphHash())
                .isNotEqualTo(base.graphHash());

        // The frozen effective scope is part of the material: a narrower intersection is a different graph.
        ReferenceGraph wide = graph(List.of(candidate("n", version)), catalogWith(baseline));
        ReferenceGraph narrowed = graph(List.of(candidate("n", version)), catalogWith(new AudienceVersionSnapshot(
                SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM, version, SubjectKind.MEMBER,
                baseline.canonicalAstHash(), baseline.semanticQueryPlanHash(),
                SegmentReferenceFixtures.scope(SegmentReferenceFixtures.NARROW_SCOPE), List.of(),
                baseline.dependencyGraphHash(), baseline.publishedAt(),
                AudienceVersionAvailability.AVAILABLE, 1)));
        assertThat(narrowed.graphHash()).isNotEqualTo(wide.graphHash());
    }

    @Test
    void theHashIsAPureFunctionOfTheFrozenMaterial() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        ReferenceGraph graph = graph(List.of(candidate("n", version)), catalogWith(snapshot(version)));

        // Rebuilding the same record from the same material reproduces the same hash, and a forged one is
        // refused by construction rather than published.
        ReferenceGraph rebuilt = ReferenceGraph.of(graph.versions());
        assertThat(rebuilt.graphHash()).isEqualTo(graph.graphHash());

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new ReferenceGraph(
                ReferenceGraph.GRAPH_FORMAT_VERSION, graph.versions(), hash("forged"),
                graph.distinctVersions(), graph.maxDepth(), graph.expandedComplexity())))
                .isInstanceOf(ReferenceException.class);
    }

    @Test
    void theEmptyGraphStillHasOneStableHash() {
        ReferenceGraph empty = graph(List.of(), catalogWith());
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.graphHash()).isEqualTo(ReferenceGraph.of(List.of()).graphHash());
        assertThat(empty.expandedComplexity()).isZero();
        assertThat(empty.maxDepth()).isZero();
    }

    @Test
    void anIndirectChangeOnTheSecondLevelMovesTheHashToo() {
        AudienceVersionRef parent = ref("audience-parent", "version-1");
        AudienceVersionRef child = ref("audience-child", "version-1");

        ReferenceGraph baseline = graph(List.of(candidate("n", parent)), catalogWith(
                snapshot(parent, List.of(child)), snapshot(child)));
        ReferenceGraph changedChild = graph(List.of(candidate("n", parent)), catalogWith(
                snapshot(parent, List.of(child)),
                snapshot(child, SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM,
                        SubjectKind.MEMBER, SegmentReferenceFixtures.NARROW_SCOPE, List.of(), 4,
                        AudienceVersionAvailability.AVAILABLE)));

        assertThat(changedChild.graphHash()).isNotEqualTo(baseline.graphHash());
        assertThat(baseline.maxDepth()).isEqualTo(2);
    }
}