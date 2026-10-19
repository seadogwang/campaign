package com.company.campaign.segment.canonical;

import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The canonical Existing Audience Reference node: a leaf over one immutable pin, with no trusted fact and no
 * mutable pointer anywhere in its bytes.
 */
class CanonicalAudienceReferenceTest {

    private static final CanonicalSegmentCodec CODEC = new CanonicalSegmentCodec();

    private static ContentHash hash(String text) {
        return ContentHash.sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static CanonicalAudienceVersionRef pin(String audienceId, String versionId) {
        return new CanonicalAudienceVersionRef(audienceId, versionId, hash(audienceId + "/" + versionId));
    }

    @Test
    void serializesAsOneExactImmutableThreePartPin() {
        CanonicalNode.AudienceReference node = new CanonicalNode.AudienceReference(pin("audience-vip", "version-7"));

        assertThat(new String(CODEC.writeNode(node), StandardCharsets.UTF_8)).isEqualTo(
                "{\"reference\":{\"audienceId\":\"audience-vip\",\"versionHash\":\""
                        + hash("audience-vip/version-7").value()
                        + "\",\"versionId\":\"version-7\"},\"type\":\"AUDIENCE_REFERENCE\"}");
    }

    @Test
    void itsRecordComponentsAreOnlyTheExactPin() {
        List<String> components = Arrays.stream(CanonicalAudienceVersionRef.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(components).containsExactly("audienceId", "versionId", "versionHash");
        // Nothing about ownership, scope, availability, retention or dependencies can be attached to a
        // canonical reference, so two identical pins canonicalize identically in two different tenants.
        assertThat(components).doesNotContain(
                "tenantId", "programId", "dataScopeId", "subject", "availability", "dependencies",
                "current", "latest");
    }

    @Test
    void theSamePinAlwaysHashesIdenticallyAndADifferentPinNeverDoes() {
        CanonicalNode.AudienceReference first = new CanonicalNode.AudienceReference(pin("audience-vip", "version-7"));
        CanonicalNode.AudienceReference same = new CanonicalNode.AudienceReference(pin("audience-vip", "version-7"));
        CanonicalNode.AudienceReference otherVersion =
                new CanonicalNode.AudienceReference(pin("audience-vip", "version-8"));
        CanonicalNode.AudienceReference otherAudience =
                new CanonicalNode.AudienceReference(pin("audience-other", "version-7"));
        CanonicalNode.AudienceReference changedBytes = new CanonicalNode.AudienceReference(
                new CanonicalAudienceVersionRef("audience-vip", "version-7", hash("changed")));

        assertThat(CODEC.hashNode(first)).isEqualTo(CODEC.hashNode(same));
        assertThat(CODEC.hashNode(first)).isNotEqualTo(CODEC.hashNode(otherVersion));
        assertThat(CODEC.hashNode(first)).isNotEqualTo(CODEC.hashNode(otherAudience));
        // A same-identity changed-bytes pin is a different canonical meaning, not the same one.
        assertThat(CODEC.hashNode(first)).isNotEqualTo(CODEC.hashNode(changedBytes));
    }

    @Test
    void aReferenceLeafComposesUnderEveryGroupingWithoutChangingShape() {
        CanonicalNode reference = new CanonicalNode.AudienceReference(pin("audience-vip", "version-7"));

        assertThat(new String(CODEC.writeNode(new CanonicalNode.Negation(reference)), StandardCharsets.UTF_8))
                .contains("\"type\":\"NOT\"")
                .contains("\"type\":\"AUDIENCE_REFERENCE\"");
        assertThat(new String(
                CODEC.writeNode(new CanonicalNode.Group(CanonicalNode.GroupKind.ALL, List.of(reference))),
                StandardCharsets.UTF_8))
                .contains("\"type\":\"ALL\"")
                .contains("\"type\":\"AUDIENCE_REFERENCE\"");
    }

    @Test
    void theCanonicalContractsRefuseAnIncompleteOrUnboundedPin() {
        assertThatThrownBy(() -> new CanonicalNode.AudienceReference(null))
                .isInstanceOf(CanonicalInvariantException.class);
        assertThatThrownBy(() -> new CanonicalAudienceVersionRef(null, "version-7", hash("x")))
                .isInstanceOf(CanonicalInvariantException.class);
        assertThatThrownBy(() -> new CanonicalAudienceVersionRef("audience-vip", null, hash("x")))
                .isInstanceOf(CanonicalInvariantException.class);
        assertThatThrownBy(() -> new CanonicalAudienceVersionRef("audience-vip", "version-7", null))
                .isInstanceOf(CanonicalInvariantException.class);
        assertThatThrownBy(() -> new CanonicalAudienceVersionRef("audience vip", "version-7", hash("x")))
                .isInstanceOf(CanonicalInvariantException.class);
    }

    @Test
    void aReferenceIsNotAMetricSoItAddsNoAggregateToAnExistsScope() {
        CanonicalNode reference = new CanonicalNode.AudienceReference(pin("audience-vip", "version-7"));
        assertThat(CanonicalInvariants.containsMetric(reference)).isFalse();
    }
}