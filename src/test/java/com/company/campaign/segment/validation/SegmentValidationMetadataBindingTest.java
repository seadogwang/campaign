package com.company.campaign.segment.validation;

import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirement 2: wrong snapshot ID/version/hash and trusted owner mismatch all fail closed. */
class SegmentValidationMetadataBindingTest {

    private static final ContentHash OTHER_HASH =
            ContentHash.sha256("foreign-snapshot".getBytes(StandardCharsets.UTF_8));

    private static SegmentDsl withBinding(MetadataBinding binding) {
        Predicate root = SegmentValidationFixtures.all("n-root", List.of(
                SegmentValidationFixtures.tierEquals("n-tier", "GOLD")));
        return SegmentValidationFixtures.publish(binding, root);
    }

    private static SegmentValidationResult validateWith(MetadataBinding binding) {
        return SegmentValidationFixtures.validate(
                withBinding(binding), SegmentValidationFixtures.snapshot(), SegmentValidationFixtures.context(),
                SegmentValidationFixtures.allowAll(), SegmentValidationFixtures.policy());
    }

    @Test
    void wrongSnapshotIdFailsClosedOnTheSnapshotIdPin() {
        SegmentValidationResult result = validateWith(SegmentValidationFixtures.binding("metadata-999"));

        assertThat(result.valid()).isFalse();
        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(SegmentValidationErrorCodes.METADATA_BINDING_MISMATCH);
        assertThat(result.issues().get(0).details()).containsEntry("pin", "SNAPSHOT_ID");
    }

    @Test
    void wrongSnapshotVersionFailsClosedOnTheVersionPin() {
        SegmentValidationResult result = validateWith(
                SegmentValidationFixtures.binding(SegmentValidationFixtures.SNAPSHOT_ID, "2"));

        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(SegmentValidationErrorCodes.METADATA_BINDING_MISMATCH);
        assertThat(result.issues().get(0).details()).containsEntry("pin", "SNAPSHOT_VERSION");
    }

    @Test
    void wrongContentHashFailsClosedOnTheHashPin() {
        SegmentValidationResult result = validateWith(new MetadataBinding(
                SegmentValidationFixtures.SNAPSHOT_ID, SegmentValidationFixtures.SNAPSHOT_VERSION,
                com.company.campaign.metadata.MetadataSchemaVersions.SUPPORTED, OTHER_HASH));

        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(SegmentValidationErrorCodes.METADATA_BINDING_MISMATCH);
        assertThat(result.issues().get(0).details()).containsEntry("pin", "CONTENT_HASH");
        assertThat(result.canonicalSegment()).isNull();
    }

    @Test
    void trustedOwnerMismatchFailsClosedBeforeAnyDefinitionIsAuthorized() {
        SegmentValidationResult result = SegmentValidationFixtures.validate(
                SegmentValidationFixtures.validDocument(), SegmentValidationFixtures.snapshot(),
                SegmentValidationFixtures.foreignOwnerContext(), SegmentValidationFixtures.allowAll(),
                SegmentValidationFixtures.policy());

        assertThat(result.valid()).isFalse();
        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(SegmentValidationErrorCodes.METADATA_OWNER_MISMATCH);
        assertThat(result.canonicalSegment()).isNull();
    }

    @Test
    void aForeignMetadataSchemaVersionCannotEvenBeBound() {
        // The schema-version pin is structurally closed by T05: a binding naming another Metadata schema
        // version is refused at construction, so it can never reach the validator as a soft mismatch.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new MetadataBinding(
                SegmentValidationFixtures.SNAPSHOT_ID, SegmentValidationFixtures.SNAPSHOT_VERSION,
                new com.company.campaign.contracts.SchemaVersion(2, 0),
                SegmentValidationFixtures.snapshot().contentHash()))
                .isInstanceOf(com.company.campaign.segment.dsl.contract.SegmentDslException.class)
                .hasFieldOrPropertyWithValue("code",
                        com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes.METADATA_BINDING_INVALID);
    }

    @Test
    void aBindingMismatchNeverFallsThroughToDefinitionValidation() {
        // The document references a valid definition, but the binding names a different snapshot: the run must
        // stop at the binding stage rather than resolving anything against a snapshot it did not bind.
        SegmentValidationResult result = validateWith(SegmentValidationFixtures.binding("metadata-404"));

        assertThat(SegmentValidationFixtures.codes(result))
                .doesNotContain(SegmentValidationErrorCodes.DEFINITION_UNKNOWN)
                .doesNotContain(SegmentValidationErrorCodes.RESOURCE_NOT_ACCESSIBLE);
    }
}