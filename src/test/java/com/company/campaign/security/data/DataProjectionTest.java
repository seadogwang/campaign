package com.company.campaign.security.data;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Classification-aware projection: allowlists, RESTRICTED exclusion, recursive redaction, immutability and
 * payload limits.
 */
class DataProjectionTest {

    private final CanonicalJson canonicalJson = new CanonicalJson();

    // Requirement 10: prompt projection excludes RESTRICTED and non-allowlisted fields.
    @Test
    void promptProjectionExcludesRestrictedAndNonAllowlistedFields() {
        ClassifiedPayload payload = ClassifiedPayload.of(
                ClassifiedField.of("publicField", DataClassification.PUBLIC, "ok"),
                ClassifiedField.of("restrictedField", DataClassification.RESTRICTED, "member-42"),
                ClassifiedField.of("otherField", DataClassification.PUBLIC, "not-allowlisted"));

        ProjectionPolicy policy = ProjectionPolicy.forChannel(
                ProjectionChannel.MODEL_PROMPT, Set.of("publicField", "restrictedField"));
        ProjectionResult result = policy.project(payload);

        assertThat(result.values()).containsOnlyKeys("publicField");
        assertThat(result.values()).doesNotContainKey("restrictedField");
        assertThat(result.omittedFields()).containsExactlyInAnyOrder("restrictedField", "otherField");
        assertThat(result.channel()).isEqualTo(ProjectionChannel.MODEL_PROMPT);
    }

    // Requirement 10: requiring a withheld field fails closed with a stable code.
    @Test
    void requiringAWithheldFieldFailsClosed() {
        ClassifiedPayload payload = ClassifiedPayload.of(
                ClassifiedField.of("restrictedField", DataClassification.RESTRICTED, "member-42"));
        ProjectionPolicy policy = ProjectionPolicy.forChannel(
                ProjectionChannel.MODEL_PROMPT, Set.of("restrictedField"));

        ContractAssertions.assertFailsWithCode(
                () -> policy.requireProjected(payload, "restrictedField"),
                ContractErrorCodes.PROJECTION_FIELD_DENIED);
    }

    // Requirement 11: nested secrets and PII are redacted from log and audit projections.
    @Test
    void secretsAndPiiAreRedactedFromLogAndAuditProjections() {
        ClassifiedPayload payload = nestedSensitivePayload();

        for (ProjectionChannel channel : List.of(
                ProjectionChannel.APPLICATION_LOG, ProjectionChannel.AUDIT_METADATA)) {
            ProjectionPolicy policy = ProjectionPolicy.forChannel(channel, Set.of("record"));
            Map<String, Object> projected = asMap(policy.project(payload).values().get("record"));

            String rendered = canonicalJson.writeString(projected);
            assertThat(rendered).as("channel %s", channel)
                    .doesNotContain(
                            "tok-123", "Bearer abc", "session=xyz", "p@ss",
                            "member-42", "a@example.com", "+8613800000000");
            assertThat(rendered).contains(Redaction.REDACTED);

            assertThat(projected.get("memberId")).isEqualTo(Redaction.REDACTED);
            assertThat(projected.get("email")).isEqualTo(Redaction.REDACTED);
            assertThat(projected.get("phone")).isEqualTo(Redaction.REDACTED);

            Map<String, Object> nested = asMap(projected.get("nested"));
            assertThat(nested.get("token")).isEqualTo(Redaction.REDACTED);
            assertThat(nested.get("authorizationHeader")).isEqualTo(Redaction.REDACTED);
            assertThat(nested.get("cookie")).isEqualTo(Redaction.REDACTED);

            List<Object> items = asList(nested.get("items"));
            assertThat(asMap(items.get(0)).get("password")).isEqualTo(Redaction.REDACTED);
        }
    }

    // Requirement 12: projected maps and collections are deeply immutable.
    @Test
    void projectedMapsAndCollectionsAreDeeplyImmutable() {
        ProjectionPolicy policy = ProjectionPolicy.forChannel(ProjectionChannel.APPLICATION_LOG, Set.of("record"));
        ProjectionResult result = policy.project(nestedSensitivePayload());

        assertThatThrownBy(() -> result.values().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.omittedFields().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);

        Map<String, Object> projected = asMap(result.values().get("record"));
        assertThatThrownBy(() -> projected.put("x", 1)).isInstanceOf(UnsupportedOperationException.class);

        Map<String, Object> nested = asMap(projected.get("nested"));
        assertThatThrownBy(() -> nested.put("y", 1)).isInstanceOf(UnsupportedOperationException.class);

        List<Object> items = asList(nested.get("items"));
        assertThatThrownBy(items::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> asMap(items.get(0)).put("z", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // Requirement 13: size, depth and collection limits fail deterministically with stable codes.
    @Test
    void sizeDepthAndCollectionLimitsFailWithStableCodes() {
        ClassifiedPayload sizePayload = ClassifiedPayload.of(
                ClassifiedField.of("blob", DataClassification.PUBLIC, "x".repeat(500)));
        ContractAssertions.assertFailsWithCode(
                () -> policy(new ProjectionLimits(64, 16, 1_000)).project(sizePayload),
                ContractErrorCodes.PAYLOAD_SIZE_LIMIT_EXCEEDED);

        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("value", "x");
        Map<String, Object> middle = new LinkedHashMap<>();
        middle.put("inner", inner);
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("middle", middle);
        ClassifiedPayload depthPayload = ClassifiedPayload.of(
                ClassifiedField.of("tree", DataClassification.PUBLIC, outer));
        ContractAssertions.assertFailsWithCode(
                () -> policy(new ProjectionLimits(65_536, 2, 1_000)).project(depthPayload),
                ContractErrorCodes.PAYLOAD_DEPTH_LIMIT_EXCEEDED);

        List<Object> many = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            many.add("value-" + index);
        }
        ClassifiedPayload collectionPayload = ClassifiedPayload.of(
                ClassifiedField.of("list", DataClassification.PUBLIC, many));
        ContractAssertions.assertFailsWithCode(
                () -> policy(new ProjectionLimits(65_536, 16, 10)).project(collectionPayload),
                ContractErrorCodes.PAYLOAD_COLLECTION_LIMIT_EXCEEDED);
    }

    private static ProjectionPolicy policy(ProjectionLimits limits) {
        return new ProjectionPolicy(
                ProjectionChannel.APPLICATION_LOG,
                Set.of("blob", "tree", "list"),
                DataClassification.PUBLIC,
                limits);
    }

    private static ClassifiedPayload nestedSensitivePayload() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("password", "p@ss");

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("token", "tok-123");
        nested.put("authorizationHeader", "Bearer abc");
        nested.put("cookie", "session=xyz");
        nested.put("items", List.of(item));

        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("nested", nested);
        sample.put("memberId", "member-42");
        sample.put("email", "a@example.com");
        sample.put("phone", "+8613800000000");

        return ClassifiedPayload.of(ClassifiedField.of("record", DataClassification.INTERNAL, sample));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return (List<Object>) value;
    }
}