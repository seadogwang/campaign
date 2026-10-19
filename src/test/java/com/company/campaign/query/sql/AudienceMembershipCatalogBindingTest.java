package com.company.campaign.query.sql;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T18: the audience membership binding is an optional part of the storage catalog document.
 *
 * <p>A catalog authored before Existing Audience References existed carries no such field, so it must keep
 * loading and binding to {@code null} — which is exactly why a reference compiled against it fails closed
 * rather than weakening. A catalog that does declare the binding must round-trip through the same strict
 * read boundary, and the binding must be part of the catalog content hash.
 */
class AudienceMembershipCatalogBindingTest {

    @Test
    void aCatalogResourceWithoutTheMembershipFieldStillLoadsAndBindsNothing() {
        StorageCatalog legacy = StorageCatalogResourceLoader.load("metadata/core-loyalty-storage-v9.json");

        assertThat(legacy.audienceMembership()).isNull();
        // The mandatory DataScope binding is unaffected: only the reference-specific binding is optional.
        assertThat(legacy.dataScope()).isNotNull();
    }

    @Test
    void aCatalogResourceCarryingTheMembershipBindingRoundTripsThroughTheStrictBoundary() {
        StorageCatalog withBinding = SqlTestSupport.catalogWithAudienceMembership();
        StorageCatalogResource resource = new StorageCatalogResource(
                withBinding.catalogVersion(),
                withBinding.entities(),
                withBinding.attributes(),
                withBinding.metrics(),
                withBinding.relations(),
                withBinding.dataScope(),
                withBinding.audienceMembership(),
                withBinding.fxStrategy(),
                withBinding.refundStorage());

        StorageCatalog reparsed = new CanonicalJson()
                .read(new CanonicalJson().writeString(resource), StorageCatalogResource.class)
                .publish();

        assertThat(reparsed.audienceMembership()).isEqualTo(SqlTestSupport.audienceMembership());
        assertThat(reparsed.contentHash()).isEqualTo(withBinding.contentHash());
    }

    @Test
    void theMembershipBindingIsPartOfTheCatalogContentHash() {
        StorageCatalog without = SqlTestSupport.standardCatalog();
        StorageCatalog with = SqlTestSupport.catalogWithAudienceMembership();

        assertThat(with.audienceMembership()).isNotNull();
        assertThat(without.audienceMembership()).isNull();
        assertThat(with.contentHash()).isNotEqualTo(without.contentHash());
    }

    @Test
    void aMembershipBindingNamingAnUnmappedMemberEntityIsRejectedAtPublication() {
        // The membership table keys on an exact member entity version, so a catalog that binds one while
        // declaring no storage for it is contradictory material and cannot be published at all.
        ContractAssertions.assertFailsWithCode(
                () -> StorageCatalog.publish(
                        SqlTestSupport.CATALOG_VERSION,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        SqlTestSupport.standardCatalog().dataScope(),
                        SqlTestSupport.audienceMembership(),
                        null,
                        null),
                SqlErrorCodes.SQL_CATALOG_INVALID);
        assertThat(SqlVersions.CATALOG_FORMAT_VERSION).isEqualTo("segment-storage-catalog/1.2");
    }
}