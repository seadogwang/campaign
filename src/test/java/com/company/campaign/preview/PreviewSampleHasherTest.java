package com.company.campaign.preview;

import com.company.campaign.security.data.SafeDerivedValue;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requirement 12: authorized samples are deterministic keyed HMAC-SHA-256 outputs in canonical
 * {@code SafeDerivedValue.MEMBER_ID_HASH} form; the same context/member/key is deterministic and a different
 * key, context or member changes the output. The key is never exposed.
 */
class PreviewSampleHasherTest {

    private static final String TENANT = "tenant-001";
    private static final String PROGRAM = "program-001";
    private static final String SCOPE = "scope-008";

    @Test
    void theSameContextAndMemberIsDeterministic() {
        PreviewSampleHasher hasher = new PreviewSampleHasher(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        SafeDerivedValue first = hasher.hash(TENANT, PROGRAM, SCOPE, "m-001");
        SafeDerivedValue second = hasher.hash(TENANT, PROGRAM, SCOPE, "m-001");

        assertThat(first).isEqualTo(second);
        assertThat(first.kind()).isEqualTo(SafeDerivedValue.DerivedKind.MEMBER_ID_HASH);
        assertThat(first.value()).matches("^sha256:[0-9a-f]{64}$");
    }

    @Test
    void aDifferentMemberContextOrKeyChangesTheOutput() {
        PreviewSampleHasher hasher = new PreviewSampleHasher(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        PreviewSampleHasher otherKey = new PreviewSampleHasher(
                "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));

        String baseline = hasher.hash(TENANT, PROGRAM, SCOPE, "m-001").value();

        assertThat(hasher.hash(TENANT, PROGRAM, SCOPE, "m-002").value()).isNotEqualTo(baseline);
        assertThat(hasher.hash("tenant-002", PROGRAM, SCOPE, "m-001").value()).isNotEqualTo(baseline);
        assertThat(hasher.hash(TENANT, "program-002", SCOPE, "m-001").value()).isNotEqualTo(baseline);
        assertThat(hasher.hash(TENANT, PROGRAM, "scope-009", "m-001").value()).isNotEqualTo(baseline);
        assertThat(otherKey.hash(TENANT, PROGRAM, SCOPE, "m-001").value()).isNotEqualTo(baseline);
    }

    @Test
    void theKeyIsDefensivelyCopiedAndNeverRendered() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        PreviewSampleHasher hasher = new PreviewSampleHasher(key);
        String baseline = hasher.hash(TENANT, PROGRAM, SCOPE, "m-001").value();

        // Mutating the caller's array must not change the hasher's behavior.
        java.util.Arrays.fill(key, (byte) 'x');
        assertThat(hasher.hash(TENANT, PROGRAM, SCOPE, "m-001").value()).isEqualTo(baseline);

        assertThat(hasher.toString()).doesNotContain("0123456789abcdef0123456789abcdef");
        assertThat(hasher.toString()).contains("HmacSHA256");
    }

    @Test
    void aShortKeyIsRejected() {
        assertThatThrownBy(() -> new PreviewSampleHasher(new byte[16]))
                .isInstanceOf(PreviewException.class)
                .extracting(failure -> ((PreviewException) failure).code())
                .isEqualTo(PreviewErrorCodes.DEPENDENCY_MISSING);
        assertThatThrownBy(() -> new PreviewSampleHasher(null))
                .isInstanceOf(PreviewException.class);
    }
}