package com.company.campaign.segment.reference;

import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.AUDIENCE_SCOPE;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.hash;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.ref;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The in-memory catalog: exact-identity lookup, refusal to overwrite published bytes, and observable
 * availability transitions.
 */
class InMemoryAudienceVersionCatalogTest {

    @Test
    void answersOnlyAnExactPinAndNeverAMutablePointer() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        InMemoryAudienceVersionCatalog catalog = SegmentReferenceFixtures.catalogWith(snapshot(version));

        assertThat(catalog.findExact(version)).contains(snapshot(version));
        assertThat(catalog.findExact(ref("audience-vip", "version-8"))).isEmpty();
        assertThat(catalog.findExact(ref("audience-other", "version-7"))).isEmpty();
        assertThat(catalog.findExact(null)).isEmpty();
        // The same identity with different bytes is not a hit: the pin is the key, not the version ID alone.
        assertThat(catalog.findExact(new AudienceVersionRef(
                version.audienceId(), version.versionId(), hash("changed")))).contains(snapshot(version));
    }

    @Test
    void refusesToOverwritePublishedBytesUnderTheSameVersionIdentity() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        InMemoryAudienceVersionCatalog catalog = SegmentReferenceFixtures.catalogWith(snapshot(version));

        // Re-publishing byte-identical material is idempotent.
        assertThat(catalog.publish(snapshot(version))).isEqualTo(snapshot(version));

        AudienceVersionSnapshot changed = new AudienceVersionSnapshot(
                SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM, version, SubjectKind.MEMBER,
                hash("different-ast"), hash("plan"), SegmentReferenceFixtures.scope(AUDIENCE_SCOPE), List.of(),
                hash("graph"), java.time.Instant.parse("2026-09-02T00:00:00Z"),
                AudienceVersionAvailability.AVAILABLE, 1);

        assertThatThrownBy(() -> catalog.publish(changed))
                .isInstanceOf(ReferenceException.class)
                .hasFieldOrPropertyWithValue("code", ReferenceErrorCodes.VERSION_CONFLICT);
        assertThat(catalog.findExact(version)).contains(snapshot(version));
    }

    @Test
    void surfacesAvailabilitySeparatelyFromContent() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        InMemoryAudienceVersionCatalog catalog = SegmentReferenceFixtures.catalogWith(snapshot(version));

        assertThat(catalog.currentAvailability(version)).isEqualTo(AudienceVersionAvailability.AVAILABLE);

        catalog.updateAvailability(version, AudienceVersionAvailability.UNAVAILABLE);
        assertThat(catalog.currentAvailability(version)).isEqualTo(AudienceVersionAvailability.UNAVAILABLE);
        assertThat(catalog.findExact(version).orElseThrow().availability())
                .isEqualTo(AudienceVersionAvailability.UNAVAILABLE);

        catalog.updateAvailability(version, AudienceVersionAvailability.DELETED);
        assertThat(catalog.findExact(version).orElseThrow().usable()).isFalse();

        assertThatThrownBy(() -> catalog.updateAvailability(
                ref("audience-missing", "version-1"), AudienceVersionAvailability.DELETED))
                .isInstanceOf(ReferenceException.class)
                .hasFieldOrPropertyWithValue("code", ReferenceErrorCodes.VERSION_NOT_FOUND);
    }

    @Test
    void anUnknownVersionIsNeverReportedAsAvailable() {
        InMemoryAudienceVersionCatalog catalog = SegmentReferenceFixtures.catalogWith();
        assertThat(catalog.currentAvailability(ref("audience-missing", "version-1")))
                .isEqualTo(AudienceVersionAvailability.UNAVAILABLE);
        assertThat(catalog.currentAvailability(null)).isEqualTo(AudienceVersionAvailability.UNAVAILABLE);
    }
}