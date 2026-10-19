package com.company.campaign.security.data;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for the T02-R1 boundary findings.
 *
 * <p>Finding 1: a top-level field whose <em>name</em> is secret or PII must be replaced as a whole. The
 * original implementation only inspected the field's children, so an allowlisted scalar named {@code email},
 * {@code token} or {@code memberId} was listed in {@code redactedFields} while its raw value stayed in
 * {@code values}.
 *
 * <p>Finding 2: the removed {@code safeDerivedFields} string set let a caller exempt a raw sensitive name
 * by repeating that name. A sensitive name must never be exemptible.
 */
class ProjectionBoundaryRegressionTest {

    private static final Set<String> SENSITIVE_NAMES =
            Set.of("token", "authorization", "cookie", "memberId", "email", "phone");

    private final CanonicalJson canonicalJson = new CanonicalJson();

    /** Finding 1: top-level scalar secrets and PII are absent from every channel. */
    @Test
    void topLevelSensitiveScalarsAreReplacedInEveryChannel() {
        ClassifiedPayload payload = ClassifiedPayload.of(
                ClassifiedField.of("token", DataClassification.CONFIDENTIAL, "tok-123"),
                ClassifiedField.of("authorization", DataClassification.CONFIDENTIAL, "Bearer abc"),
                ClassifiedField.of("cookie", DataClassification.CONFIDENTIAL, "session=xyz"),
                ClassifiedField.of("memberId", DataClassification.CONFIDENTIAL, "member-42"),
                ClassifiedField.of("email", DataClassification.CONFIDENTIAL, "a@example.com"),
                ClassifiedField.of("phone", DataClassification.CONFIDENTIAL, "+8613800000000"),
                ClassifiedField.of("safeField", DataClassification.INTERNAL, "kept"));

        for (ProjectionChannel channel : ProjectionChannel.values()) {
            ProjectionResult result = ProjectionPolicy.forChannel(
                            channel, Set.of("token", "authorization", "cookie", "memberId", "email", "phone", "safeField"))
                    .project(payload);

            assertThat(result.values()).as("channel %s", channel)
                    .containsEntry("safeField", "kept");
            for (String name : SENSITIVE_NAMES) {
                assertThat(result.values().get(name))
                        .as("channel %s field %s", channel, name)
                        .isEqualTo(Redaction.REDACTED);
            }
            assertThat(result.redactedFields())
                    .as("channel %s", channel)
                    .containsExactlyInAnyOrderElementsOf(SENSITIVE_NAMES);

            String rendered = canonicalJson.writeString(result.values());
            assertThat(rendered).as("channel %s", channel)
                    .doesNotContain(
                            "tok-123", "Bearer abc", "session=xyz",
                            "member-42", "a@example.com", "+8613800000000");
        }
    }

    /** Finding 1: a top-level structured value under a sensitive name is replaced as a whole. */
    @Test
    void topLevelSensitiveStructuredValuesAreReplacedInEveryChannel() {
        ClassifiedPayload payload = ClassifiedPayload.of(
                ClassifiedField.of("token", DataClassification.CONFIDENTIAL, structured("tok-123")),
                ClassifiedField.of("authorization", DataClassification.CONFIDENTIAL, structured("Bearer abc")),
                ClassifiedField.of("cookie", DataClassification.CONFIDENTIAL, structured("session=xyz")),
                ClassifiedField.of("memberId", DataClassification.CONFIDENTIAL, structured("member-42")),
                ClassifiedField.of("email", DataClassification.CONFIDENTIAL, List.of("a@example.com")),
                ClassifiedField.of("phone", DataClassification.CONFIDENTIAL, structured("+8613800000000")));

        for (ProjectionChannel channel : ProjectionChannel.values()) {
            ProjectionResult result = ProjectionPolicy.forChannel(channel, SENSITIVE_NAMES)
                    .project(payload);

            for (String name : SENSITIVE_NAMES) {
                assertThat(result.values().get(name)).as("channel %s field %s", channel, name)
                        .isEqualTo(Redaction.REDACTED);
            }
            assertThat(result.redactedFields()).containsExactlyInAnyOrderElementsOf(SENSITIVE_NAMES);

            String rendered = canonicalJson.writeString(result.values());
            assertThat(rendered).as("channel %s", channel)
                    .doesNotContain(
                            "tok-123", "Bearer abc", "session=xyz",
                            "member-42", "a@example.com", "+8613800000000");
        }
    }

    /** Finding 2: a raw sensitive name cannot be exempted, and the exemption API no longer exists. */
    @Test
    void rawSensitiveNamesCannotBeMarkedSafe() {
        ClassifiedPayload payload = ClassifiedPayload.of(
                ClassifiedField.of("token", DataClassification.CONFIDENTIAL, "tok-123"),
                ClassifiedField.of("memberId", DataClassification.CONFIDENTIAL, "member-42"),
                ClassifiedField.of("email", DataClassification.CONFIDENTIAL, "a@example.com"));

        ProjectionPolicy policy = ProjectionPolicy.forChannel(ProjectionChannel.APPLICATION_LOG, SENSITIVE_NAMES);
        ProjectionResult result = policy.project(payload);

        for (String name : List.of("token", "memberId", "email")) {
            assertThat(result.values().get(name)).as("field %s", name)
                    .isEqualTo(Redaction.REDACTED);
        }
        assertThat(canonicalJson.writeString(result.values()))
                .doesNotContain("tok-123", "member-42", "a@example.com");

        // The typed replacement refuses the raw source value, so "marking it safe" is not expressible.
        assertThatThrownBy(() -> SafeDerivedValue.emailDomain("a@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("a@example.com");
        assertThatThrownBy(() -> SafeDerivedValue.phoneCountryCode("+8613800000000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("+8613800000000");
        assertThatThrownBy(() -> SafeDerivedValue.of(
                SafeDerivedValue.DerivedKind.MEMBER_ID_HASH, "member-42"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("member-42");
    }

    /** Finding 2: a genuine derived representation is projected, and only for PII names. */
    @Test
    void validatedDerivedValuesAreProjectedButNeverExemptSecrets() {
        ContentHash memberIdHash = ContentHash.sha256(
                "member-42".getBytes(StandardCharsets.UTF_8));

        ClassifiedPayload payload = ClassifiedPayload.of(
                ClassifiedField.of("emailDomain", DataClassification.INTERNAL, "example.com"),
                ClassifiedField.of("memberIdHash", DataClassification.INTERNAL, memberIdHash.value()),
                ClassifiedField.of("phoneCountryCode", DataClassification.INTERNAL, "+86"),
                // A PII name may carry a validated derived value: the type proves it is not the raw value.
                ClassifiedField.of("email", DataClassification.INTERNAL,
                        SafeDerivedValue.emailDomain("example.com")),
                // A secret name is never exemptible, even with a validated derived value.
                ClassifiedField.of("token", DataClassification.INTERNAL,
                        SafeDerivedValue.emailDomain("example.com")));

        ProjectionResult result = ProjectionPolicy.forChannel(
                        ProjectionChannel.AUDIT_METADATA,
                        Set.of("emailDomain", "memberIdHash", "phoneCountryCode", "email", "token"))
                .project(payload);

        assertThat(result.values())
                .containsEntry("emailDomain", "example.com")
                .containsEntry("memberIdHash", memberIdHash.value())
                .containsEntry("phoneCountryCode", "+86")
                .containsEntry("email", "example.com")
                .containsEntry("token", Redaction.REDACTED);
        assertThat(result.redactedFields()).containsExactly("token");

        String rendered = canonicalJson.writeString(result.values());
        assertThat(rendered).doesNotContain("a@example.com", "member-42", "+8613800000000", "tok-123");
    }

    private static Map<String, Object> structured(String leaf) {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("value", leaf);
        return Map.of("nested", nested);
    }
}