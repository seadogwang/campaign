package com.company.campaign.segment.reference;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.AUDIENCE_SCOPE;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.hash;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.ref;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fixture membership port: exact version/hash binding, no conflation of "unknown" with "empty", and a hash
 * that never leaks the identifiers it covers.
 */
class AudienceMembershipFixtureTest {

    @Test
    void membershipIsBoundToTheExactVersionItWasReadFrom() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        FixtureAudienceMembershipDataset dataset = new FixtureAudienceMembershipDataset()
                .register(version, Set.of("member-3", "member-1", "member-2"));

        AudienceMembershipSet set = dataset.lookup(version, scope(AUDIENCE_SCOPE)).orElseThrow();

        assertThat(set.version()).isEqualTo(version);
        assertThat(set.memberIds()).containsExactly("member-1", "member-2", "member-3");
        assertThat(set.contains("member-2")).isTrue();
        assertThat(set.contains("member-9")).isFalse();
        assertThat(set.contains(null)).isFalse();

        // A different pin — even the same audience at another version — has no members here.
        assertThat(dataset.lookup(ref("audience-vip", "version-8"), scope(AUDIENCE_SCOPE))).isEmpty();
        assertThat(dataset.lookup(new AudienceVersionRef(
                version.audienceId(), version.versionId(), hash("changed")), scope(AUDIENCE_SCOPE))).isEmpty();
    }

    @Test
    void anUnknownVersionIsEmptyRatherThanAnEmptySet() {
        FixtureAudienceMembershipDataset dataset = new FixtureAudienceMembershipDataset()
                .register(ref("audience-vip", "version-7"), Set.of("member-1"));

        assertThat(dataset.lookup(ref("audience-missing", "version-1"), scope(AUDIENCE_SCOPE))).isEmpty();
        assertThat(dataset.lookup(null, scope(AUDIENCE_SCOPE))).isEmpty();
        assertThat(dataset.lookup(ref("audience-vip", "version-7"), null)).isEmpty();

        // A registered version with no members is a present, empty set: a different fact entirely.
        AudienceVersionRef empty = ref("audience-empty", "version-1");
        dataset.register(empty, Set.of());
        assertThat(dataset.lookup(empty, scope(AUDIENCE_SCOPE))).isPresent();
        assertThat(dataset.lookup(empty, scope(AUDIENCE_SCOPE)).orElseThrow().memberIds()).isEmpty();
    }

    @Test
    void theMemberSetHashCoversThePinAndTheExactMembers() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        AudienceVersionRef other = ref("audience-other", "version-1");
        FixtureAudienceMembershipDataset dataset = new FixtureAudienceMembershipDataset()
                .register(version, Set.of("member-1", "member-2"))
                .register(other, Set.of("member-1", "member-2"));

        assertThat(dataset.lookup(version, scope(AUDIENCE_SCOPE)).orElseThrow().memberSetHash())
                .isNotEqualTo(dataset.lookup(other, scope(AUDIENCE_SCOPE)).orElseThrow().memberSetHash());
        assertThat(FixtureAudienceMembershipDataset.hashOf(version, List.of("member-1", "member-2")))
                .isEqualTo(dataset.lookup(version, scope(AUDIENCE_SCOPE)).orElseThrow().memberSetHash());
        assertThat(FixtureAudienceMembershipDataset.hashOf(version, List.of("member-1")))
                .isNotEqualTo(dataset.lookup(version, scope(AUDIENCE_SCOPE)).orElseThrow().memberSetHash());
    }

    @Test
    void thePublicShapeOfAMemberSetNeverCarriesRawIdentifiers() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        AudienceMembershipSet set = new AudienceMembershipSet(
                version, List.of("member-1", "member-2"), hash("member-set"));

        assertThat(set.toString())
                .contains(version.identityKey())
                .contains("size=2")
                .doesNotContain("member-1")
                .doesNotContain("member-2");
    }

    @Test
    void aBlankMemberIdentifierIsRejected() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        FixtureAudienceMembershipDataset dataset = new FixtureAudienceMembershipDataset();

        assertThatThrownBy(() -> dataset.register(version, Set.of(" ")))
                .isInstanceOf(ReferenceException.class);
        assertThatThrownBy(() -> new AudienceMembershipSet(version, java.util.Arrays.asList("member-1", null),
                hash("x"))).isInstanceOf(ReferenceException.class);
        assertThatThrownBy(() -> new AudienceMembershipSet(version, List.of("member-1"), null))
                .isInstanceOf(ReferenceException.class);
        assertThatThrownBy(() -> new AudienceMembershipSet(null, List.of(), hash("x")))
                .isInstanceOf(ReferenceException.class);
    }
}