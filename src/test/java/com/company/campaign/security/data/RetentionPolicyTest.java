package com.company.campaign.security.data;

import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic retention decisions and refusal of raw RESTRICTED payloads. */
class RetentionPolicyTest {

    private final DefaultRetentionPolicy policy = new DefaultRetentionPolicy();

    // Requirement 14: raw RESTRICTED payloads are refused with a stable code.
    @Test
    void rawRestrictedPayloadIsRefusedWithStableCode() {
        RetentionRequest request = new RetentionRequest(
                DataClassification.RESTRICTED, ProjectionChannel.APPLICATION_LOG, true);

        RetentionDecision decision = policy.decide(request);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.storeRaw()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo(ContractErrorCodes.UNSAFE_RETENTION_REQUEST);

        ContractAssertions.assertFailsWithCode(
                () -> policy.requireRetainable(request),
                ContractErrorCodes.UNSAFE_RETENTION_REQUEST);
    }

    @Test
    void redactedRestrictedPayloadMayBeRetainedWithoutRawStorage() {
        RetentionDecision decision = policy.decide(new RetentionRequest(
                DataClassification.RESTRICTED, ProjectionChannel.AUDIT_METADATA, false));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.storeRaw()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo(DefaultRetentionPolicy.ALLOWED_REDACTED);
        assertThat(decision.retentionPeriod()).isEqualTo(Duration.ofDays(7));
    }

    // Requirement 14: deterministic decisions for allowed data.
    @Test
    void allowedClassificationsGetDeterministicDecisions() {
        RetentionDecision publicRaw = policy.decide(new RetentionRequest(
                DataClassification.PUBLIC, ProjectionChannel.APPLICATION_LOG, true));
        assertThat(publicRaw.allowed()).isTrue();
        assertThat(publicRaw.storeRaw()).isTrue();
        assertThat(publicRaw.reasonCode()).isEqualTo(DefaultRetentionPolicy.ALLOWED_RAW);
        assertThat(publicRaw.retentionPeriod()).isEqualTo(Duration.ofDays(365));

        RetentionDecision confidentialRaw = policy.decide(new RetentionRequest(
                DataClassification.CONFIDENTIAL, ProjectionChannel.APPLICATION_LOG, true));
        assertThat(confidentialRaw.allowed()).isTrue();
        assertThat(confidentialRaw.storeRaw()).isFalse();
        assertThat(confidentialRaw.retentionPeriod()).isEqualTo(Duration.ofDays(30));

        RetentionDecision prompt = policy.decide(new RetentionRequest(
                DataClassification.PUBLIC, ProjectionChannel.MODEL_PROMPT, true));
        assertThat(prompt.allowed()).isTrue();
        assertThat(prompt.storeRaw()).isFalse();

        RetentionRequest repeated = new RetentionRequest(
                DataClassification.INTERNAL, ProjectionChannel.AUDIT_METADATA, true);
        assertThat(policy.decide(repeated)).isEqualTo(policy.decide(repeated));
    }
}