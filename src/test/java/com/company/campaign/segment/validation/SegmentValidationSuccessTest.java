package com.company.campaign.segment.validation;

import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requirement 1: the exact success result, immutable output and strict Metadata binding pins. */
class SegmentValidationSuccessTest {

    @Test
    void validDocumentProducesAnExactCanonicalSuccess() {
        SegmentValidationResult result = SegmentValidationFixtures.validate(SegmentValidationFixtures.validDocument());

        assertThat(result.valid()).isTrue();
        assertThat(result.hasBlockingIssues()).isFalse();
        assertThat(result.issues()).isEmpty();
        assertThat(result.canonicalSegment()).isNotNull();
        assertThat(result.canonicalAstHash()).isNotNull();
        assertThat(result.canonicalAstHash().value()).matches("^sha256:[0-9a-f]{64}$");
        assertThat(result.dslSchemaVersion()).isEqualTo(SegmentValidationFixtures.dslVersion());
        assertThat(result.metadataBinding()).isEqualTo(SegmentValidationFixtures.binding());
        assertThat(result.canonicalizerVersion())
                .isEqualTo(com.company.campaign.segment.canonical.CanonicalizerVersions.CANONICALIZER_VERSION);
    }

    @Test
    void editableHashIsPreservedUnchangedAndStaysSeparateFromTheCanonicalHash() {
        SegmentDsl document = SegmentValidationFixtures.validDocument();
        SegmentValidationResult result = SegmentValidationFixtures.validate(document);

        assertThat(result.editableDslHash()).isEqualTo(document.editableDslHash());
        assertThat(result.canonicalAstHash()).isNotEqualTo(document.editableDslHash());
        assertThat(result.canonicalSegment().metadata()).isEqualTo(document.metadata());
        assertThat(result.canonicalSegment().subject()).isEqualTo(document.subject());
    }

    @Test
    void canonicalSegmentKeepsTheRootSubjectAndEveryValidatedNode() {
        Predicate root = SegmentValidationFixtures.all("n-root", java.util.List.of(
                SegmentValidationFixtures.tierEquals("n-tier", "GOLD"),
                SegmentValidationFixtures.attribute(
                        "n-name", "member.name", com.company.campaign.metadata.domain.ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.stringValue("Gold")))));

        SegmentValidationResult result = SegmentValidationFixtures.validate(root);

        assertThat(result.valid()).isTrue();
        com.company.campaign.segment.canonical.CanonicalNode canonical = result.canonicalSegment().predicate();
        assertThat(canonical).isInstanceOf(com.company.campaign.segment.canonical.CanonicalNode.Group.class);
        assertThat(((com.company.campaign.segment.canonical.CanonicalNode.Group) canonical).children()).hasSize(2);
    }

    @Test
    void nullDocumentFailsClosedWithAStableIssueRatherThanThrowing() {
        SegmentValidationResult result = new SegmentValidator(SegmentValidationFixtures.allowAll())
                .validate(null, SegmentValidationFixtures.snapshot(), SegmentValidationFixtures.context(),
                        SegmentValidationFixtures.policy());

        assertThat(result.valid()).isFalse();
        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(SegmentValidationErrorCodes.INPUT_MISSING);
        assertThat(result.canonicalSegment()).isNull();
        assertThat(result.canonicalAstHash()).isNull();
        assertThat(result.editableDslHash()).isNull();
    }

    @Test
    void nullSnapshotFailsClosed() {
        SegmentValidationResult result = SegmentValidationFixtures.validate(
                SegmentValidationFixtures.validDocument(), null, SegmentValidationFixtures.context(),
                SegmentValidationFixtures.allowAll(), SegmentValidationFixtures.policy());

        assertThat(result.valid()).isFalse();
        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(SegmentValidationErrorCodes.METADATA_SNAPSHOT_MISSING);
        assertThat(result.canonicalSegment()).isNull();
    }

    @Test
    void nullTrustedContextFailsClosed() {
        SegmentValidationResult result = SegmentValidationFixtures.validate(
                SegmentValidationFixtures.validDocument(), SegmentValidationFixtures.snapshot(), null,
                SegmentValidationFixtures.allowAll(), SegmentValidationFixtures.policy());

        assertThat(result.valid()).isFalse();
        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(SegmentValidationErrorCodes.TRUSTED_CONTEXT_MISSING);
    }

    @Test
    void nullPolicyFailsClosed() {
        SegmentValidationResult result = SegmentValidationFixtures.validate(
                SegmentValidationFixtures.validDocument(), SegmentValidationFixtures.snapshot(),
                SegmentValidationFixtures.context(), SegmentValidationFixtures.allowAll(), null);

        assertThat(result.valid()).isFalse();
        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(SegmentValidationErrorCodes.POLICY_MISSING);
    }

    @Test
    void resultIssueListIsImmutableAndNullHostile() {
        SegmentValidationResult result = SegmentValidationFixtures.validate(SegmentValidationFixtures.validDocument());
        assertThatThrownBy(() -> result.issues().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void resultRejectsAHashWithoutACanonicalSegment() {
        // The pairing invariant is now part of the coded canonical boundary: a forged result fails with a
        // stable code rather than an untyped argument error.
        assertThatThrownBy(() -> new SegmentValidationResult(
                com.company.campaign.segment.canonical.CanonicalizerVersions.CANONICALIZER_VERSION,
                null, null, null, null,
                com.company.campaign.shared.canonical.ContentHash.sha256(
                        "x".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                java.util.List.of()))
                .isInstanceOf(com.company.campaign.segment.canonical.CanonicalInvariantException.class)
                .hasFieldOrPropertyWithValue("code",
                        com.company.campaign.segment.canonical.CanonicalErrorCodes.CANONICAL_RESULT_INCONSISTENT);
    }
}