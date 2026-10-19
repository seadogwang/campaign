package com.company.campaign.segment.reference;

import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.AUDIENCE_SCOPE;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.hash;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.ref;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.scope;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The immutable reference contracts: what a pin is, what catalog material must prove, and which malformed or
 * mutable-pointer states are unrepresentable.
 */
class AudienceVersionContractTest {

    private static void assertInvalid(Runnable construction) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(ReferenceException.class)
                .hasFieldOrPropertyWithValue("code", ReferenceErrorCodes.INPUT_INVALID);
    }

    // ---------------------------------------------------------------- the pin

    @Test
    void aPinRequiresAllThreeExactParts() {
        AudienceVersionRef genuine = ref("audience-vip", "version-7");
        assertThat(genuine.audienceId()).isEqualTo("audience-vip");
        assertThat(genuine.versionId()).isEqualTo("version-7");
        assertThat(genuine.versionHash()).isEqualTo(hash("audience-vip/version-7"));

        assertInvalid(() -> new AudienceVersionRef(null, "version-7", hash("x")));
        assertInvalid(() -> new AudienceVersionRef("audience-vip", null, hash("x")));
        assertInvalid(() -> new AudienceVersionRef("audience-vip", "version-7", null));
        assertInvalid(() -> new AudienceVersionRef("", "version-7", hash("x")));
        assertInvalid(() -> new AudienceVersionRef("audience vip", "version-7", hash("x")));
        assertInvalid(() -> new AudienceVersionRef("audience-vip", "version/7", hash("x")));
    }

    @Test
    void twoPinsWithTheSameIdentityButDifferentBytesAreDifferentValues() {
        AudienceVersionRef first = ref("audience-vip", "version-7");
        AudienceVersionRef sameId = new AudienceVersionRef(
                first.audienceId(), first.versionId(), hash("other-bytes"));

        assertThat(first).isNotEqualTo(sameId);
        assertThat(first.sameIdentity(sameId)).isTrue();
        assertThat(sameId.sameIdentity(first)).isTrue();
        assertThat(first.identityKey()).isEqualTo(sameId.identityKey());
    }

    @Test
    void pinsOrderDeterministicallyByIdentityThenContent() {
        AudienceVersionRef a = ref("audience-a", "version-1");
        AudienceVersionRef b = ref("audience-b", "version-1");
        AudienceVersionRef aChanged = new AudienceVersionRef("audience-a", "version-1", hash("z"));

        List<AudienceVersionRef> pins = new ArrayList<>(List.of(b, aChanged, a));
        pins.sort(AudienceVersionRef.ORDER);

        assertThat(pins).containsExactly(a, aChanged, b);
    }

    // ---------------------------------------------------------------- the snapshot

    @Test
    void aSnapshotRequiresEveryTrustedFact() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        Instant published = Instant.parse("2026-09-01T00:00:00Z");

        assertInvalid(() -> new AudienceVersionSnapshot(null, "program-001", version, SubjectKind.MEMBER,
                hash("ast"), hash("plan"), scope(AUDIENCE_SCOPE), List.of(), hash("graph"), published,
                AudienceVersionAvailability.AVAILABLE, 1));
        assertInvalid(() -> new AudienceVersionSnapshot("tenant-001", "program-001", null, SubjectKind.MEMBER,
                hash("ast"), hash("plan"), scope(AUDIENCE_SCOPE), List.of(), hash("graph"), published,
                AudienceVersionAvailability.AVAILABLE, 1));
        assertInvalid(() -> new AudienceVersionSnapshot("tenant-001", "program-001", version, null,
                hash("ast"), hash("plan"), scope(AUDIENCE_SCOPE), List.of(), hash("graph"), published,
                AudienceVersionAvailability.AVAILABLE, 1));
        assertInvalid(() -> new AudienceVersionSnapshot("tenant-001", "program-001", version, SubjectKind.MEMBER,
                null, hash("plan"), scope(AUDIENCE_SCOPE), List.of(), hash("graph"), published,
                AudienceVersionAvailability.AVAILABLE, 1));
        assertInvalid(() -> new AudienceVersionSnapshot("tenant-001", "program-001", version, SubjectKind.MEMBER,
                hash("ast"), hash("plan"), null, List.of(), hash("graph"), published,
                AudienceVersionAvailability.AVAILABLE, 1));
        assertInvalid(() -> new AudienceVersionSnapshot("tenant-001", "program-001", version, SubjectKind.MEMBER,
                hash("ast"), hash("plan"), scope(AUDIENCE_SCOPE), List.of(), null, published,
                AudienceVersionAvailability.AVAILABLE, 1));
        assertInvalid(() -> new AudienceVersionSnapshot("tenant-001", "program-001", version, SubjectKind.MEMBER,
                hash("ast"), hash("plan"), scope(AUDIENCE_SCOPE), List.of(), hash("graph"), null,
                AudienceVersionAvailability.AVAILABLE, 1));
        assertInvalid(() -> new AudienceVersionSnapshot("tenant-001", "program-001", version, SubjectKind.MEMBER,
                hash("ast"), hash("plan"), scope(AUDIENCE_SCOPE), List.of(), hash("graph"), published,
                null, 1));
        // A version must contribute at least one unit of complexity, so a zero cannot silently evade the bound.
        assertInvalid(() -> new AudienceVersionSnapshot("tenant-001", "program-001", version, SubjectKind.MEMBER,
                hash("ast"), hash("plan"), scope(AUDIENCE_SCOPE), List.of(), hash("graph"), published,
                AudienceVersionAvailability.AVAILABLE, 0));
    }

    @Test
    void aVersionMayNotDeclareItselfOrRepeatADirectDependency() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        AudienceVersionRef dependency = ref("audience-other", "version-1");

        assertInvalid(() -> snapshot(version, List.of(version)));
        assertInvalid(() -> snapshot(version, List.of(dependency, dependency)));
    }

    @Test
    void directDependenciesAreHeldInCanonicalOrderWhicheverOrderTheyWereSuppliedIn() {
        AudienceVersionRef version = ref("audience-root", "version-1");
        AudienceVersionRef a = ref("audience-a", "version-1");
        AudienceVersionRef b = ref("audience-b", "version-1");

        assertThat(snapshot(version, List.of(b, a)).directDependencies()).containsExactly(a, b);
        assertThat(snapshot(version, List.of(a, b)).directDependencies()).containsExactly(a, b);
    }

    @Test
    void theSameIdentityWithDifferentContentIsNotTheSameSnapshot() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        AudienceVersionSnapshot first = snapshot(version, List.of(ref("audience-a", "version-1")));

        assertThat(first).isNotEqualTo(snapshot(version));
        assertThat(first).isNotEqualTo(snapshot(
                version, SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM, SubjectKind.MEMBER,
                AUDIENCE_SCOPE, List.of(ref("audience-a", "version-1")), 2,
                AudienceVersionAvailability.UNAVAILABLE));
    }

    // ---------------------------------------------------------------- availability and scope

    @Test
    void onlyTheAvailableStateIsUsable() {
        assertThat(AudienceVersionAvailability.AVAILABLE.isUsable()).isTrue();
        assertThat(AudienceVersionAvailability.DELETED.isUsable()).isFalse();
        assertThat(AudienceVersionAvailability.UNAVAILABLE.isUsable()).isFalse();

        AudienceVersionRef version = ref("audience-vip", "version-7");
        assertThat(snapshot(version).usable()).isTrue();
        assertThat(snapshot(version, SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM,
                SubjectKind.MEMBER, AUDIENCE_SCOPE, List.of(), 1,
                AudienceVersionAvailability.DELETED).usable()).isFalse();
    }

    @Test
    void anEffectiveScopeCarriesBothIdentityAndContentHash() {
        EffectiveDataScope genuine = scope(AUDIENCE_SCOPE);
        assertThat(genuine.dataScopeId()).isEqualTo(AUDIENCE_SCOPE);

        assertInvalid(() -> new EffectiveDataScope(null, hash("x")));
        assertInvalid(() -> new EffectiveDataScope("", hash("x")));
        assertInvalid(() -> new EffectiveDataScope(AUDIENCE_SCOPE, null));
        assertInvalid(() -> new EffectiveDataScope("not a bounded id", hash("x")));
    }

    // ---------------------------------------------------------------- policy

    @Test
    void aReferencePolicyIsBoundedAndNeverClamped() {
        ReferencePolicy standard = ReferencePolicy.standard();
        assertThat(standard.maxDepth()).isPositive();
        assertThat(standard.maxDistinctVersions()).isPositive();
        assertThat(standard.maxExpandedComplexity()).isPositive();

        assertThatThrownBy(() -> new ReferencePolicy(0, 1, 1))
                .isInstanceOf(ReferenceException.class)
                .hasFieldOrPropertyWithValue("code", ReferenceErrorCodes.POLICY_INVALID);
        assertThatThrownBy(() -> new ReferencePolicy(ReferenceLimits.MAX_DEPTH + 1, 1, 1))
                .isInstanceOf(ReferenceException.class)
                .hasFieldOrPropertyWithValue("code", ReferenceErrorCodes.POLICY_INVALID);
        assertThatThrownBy(() -> new ReferencePolicy(1, ReferenceLimits.MAX_DISTINCT_VERSIONS + 1, 1))
                .isInstanceOf(ReferenceException.class);
        assertThatThrownBy(() -> new ReferencePolicy(1, 1, ReferenceLimits.MAX_EXPANDED_COMPLEXITY + 1))
                .isInstanceOf(ReferenceException.class);
    }

    // ---------------------------------------------------------------- graph proof

    @Test
    void aReferenceGraphRefusesADeclaredHashThatIsNotItsOwnMaterial() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        ResolvedAudienceReference resolved = new ResolvedAudienceReference(
                version, snapshot(version), scope(AUDIENCE_SCOPE), 1);
        ReferenceGraph genuine = ReferenceGraph.of(List.of(resolved));

        assertThatThrownBy(() -> new ReferenceGraph(
                ReferenceGraph.GRAPH_FORMAT_VERSION, List.of(resolved), hash("forged"),
                genuine.distinctVersions(), genuine.maxDepth(), genuine.expandedComplexity()))
                .isInstanceOf(ReferenceException.class);
        assertThatThrownBy(() -> new ReferenceGraph(
                "segment-audience-reference-graph/9.9", List.of(resolved), genuine.graphHash(),
                genuine.distinctVersions(), genuine.maxDepth(), genuine.expandedComplexity()))
                .isInstanceOf(ReferenceException.class);
    }

    @Test
    void aResolvedReferenceMustResolveToExactlyThePinnedVersion() {
        AudienceVersionRef pinned = ref("audience-vip", "version-7");
        AudienceVersionRef other = ref("audience-vip", "version-8");

        assertThatThrownBy(() -> new ResolvedAudienceReference(
                pinned, snapshot(other), scope(AUDIENCE_SCOPE), 1))
                .isInstanceOf(ReferenceException.class);
        assertThatThrownBy(() -> new ResolvedAudienceReference(
                pinned, snapshot(pinned), scope(AUDIENCE_SCOPE), 0))
                .isInstanceOf(ReferenceException.class);
    }

    @Test
    void graphsOrderAndDeduplicateTheirVersionsByIdentity() {
        AudienceVersionRef b = ref("audience-b", "version-1");
        AudienceVersionRef a = ref("audience-a", "version-1");
        ReferenceGraph graph = ReferenceGraph.of(List.of(
                new ResolvedAudienceReference(b, snapshot(b), scope(AUDIENCE_SCOPE), 1),
                new ResolvedAudienceReference(a, snapshot(a), scope(AUDIENCE_SCOPE), 2)));

        assertThat(graph.versions().stream().map(ResolvedAudienceReference::reference))
                .containsExactly(a, b);
        assertThat(graph.maxDepth()).isEqualTo(2);
        assertThat(graph.expandedComplexity()).isEqualTo(2);

        assertThatThrownBy(() -> ReferenceGraph.of(List.of(
                new ResolvedAudienceReference(a, snapshot(a), scope(AUDIENCE_SCOPE), 1),
                new ResolvedAudienceReference(a, snapshot(a), scope(AUDIENCE_SCOPE), 1))))
                .isInstanceOf(ReferenceException.class);
    }
}