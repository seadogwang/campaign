package com.company.campaign.contracts;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.testsupport.ContractAssertions;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.ConstraintViolation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Trusted execution context, immutable snapshot references and server-stored Plan references.
 *
 * <p>Covers requirement 11 (Bean Validation rejects blank or malformed references and currency) and
 * requirement 13 (Plan expiry is decided by an injected clock at an exact boundary).
 */
class ExecutionContextContractTest {

    private static final Instant AS_OF = Instant.parse("2026-10-01T02:00:00Z");
    private static final Instant WATERMARK = Instant.parse("2026-10-01T01:55:00Z");

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    private final CanonicalJson canonicalJson = new CanonicalJson();

    @BeforeAll
    static void openValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    // Requirement 11: a fully valid context passes validation.
    @Test
    void validTrustedContextPassesValidation() {
        assertThat(validator.validate(validContext())).isEmpty();
    }

    @Test
    void trustedContextSerializesToAStableIsoGolden() {
        TrustedExecutionContextRef context = validContext();

        assertThat(canonicalJson.writeString(context)).isEqualTo(
                "{\"asOf\":\"2026-10-01T02:00:00Z\","
                        + "\"dataScopeId\":\"scope-008\","
                        + "\"dataWatermark\":\"2026-10-01T01:55:00Z\","
                        + "\"locale\":\"zh-CN\","
                        + "\"metadataSnapshotId\":\"metadata-17\","
                        + "\"programId\":\"program-001\","
                        + "\"reportingCurrency\":\"CNY\","
                        + "\"tenantId\":\"tenant-001\","
                        + "\"timezone\":\"Asia/Shanghai\","
                        + "\"userId\":\"user-001\"}");

        assertThat(canonicalJson.read(canonicalJson.writeString(context), TrustedExecutionContextRef.class))
                .isEqualTo(context);
    }

    @Test
    void trustedContextCarriesNoHashOrIdentifierThatCouldDrift() {
        String written = canonicalJson.writeString(validContext());

        assertThat(written).doesNotContain("contentHash", "planId", "expiresAt", "createdAt", "updatedAt");
    }

    // Requirement 11: blank and malformed references are rejected.
    @ParameterizedTest
    @ValueSource(strings = {"tenantId", "programId", "userId", "dataScopeId", "metadataSnapshotId"})
    void blankReferencesAreRejected(String field) {
        assertViolationOn(contextWith(field, ""), field);
        assertViolationOn(contextWith(field, " "), field);
        assertViolationOn(contextWith(field, "   "), field);
    }

    @ParameterizedTest
    @CsvSource({
            "tenantId, tenant/001",
            "tenantId, tenant 001",
            "tenantId, .leading-dot",
            "tenantId, tenant#001",
            "programId, program%001",
            "userId, user@example.com",
            "dataScopeId, scope 008",
            "metadataSnapshotId, metadata/17",
    })
    void malformedReferencesAreRejected(String field, String value) {
        assertViolationOn(contextWith(field, value), field);
    }

    // Requirement 11: currency must be an ISO-4217 code.
    @ParameterizedTest
    @ValueSource(strings = {"cny", "CN", "CNYY", "", "C N", "人民币", "1NY"})
    void invalidReportingCurrencyIsRejected(String currency) {
        assertViolationOn(contextWith("reportingCurrency", currency), "reportingCurrency");
    }

    @Test
    void validCurrencyCodesAreAccepted() {
        for (String currency : List.of("CNY", "USD", "EUR", "SGD")) {
            assertThat(validator.validate(contextWith("reportingCurrency", currency)))
                    .as("currency %s", currency)
                    .isEmpty();
        }
    }

    @Test
    void watermarkLaterThanAsOfIsRejected() {
        TrustedExecutionContextRef context = validContext();

        assertThatThrownBy(() -> new TrustedExecutionContextRef(
                context.tenantId(), context.programId(), context.userId(), context.dataScopeId(),
                context.metadataSnapshotId(), context.locale(), context.timezone(),
                context.reportingCurrency(), WATERMARK, AS_OF))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be after asOf");
    }

    @Test
    void localeIsCanonicalizedToASingleSpelling() {
        assertThat(new LocaleTag("zh-cn").value()).isEqualTo("zh-CN");
        assertThat(new LocaleTag("EN-us").value()).isEqualTo("en-US");
        assertThat(LocaleTag.of(Locale.SIMPLIFIED_CHINESE).value()).isEqualTo("zh-CN");
        assertThat(canonicalJson.writeString(new LocaleTag("zh-cn"))).isEqualTo("\"zh-CN\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"zh_CN", "", " ", "zh-CN-", "-zh", "zh--CN", "zh CN", "zh@CN"})
    void malformedLocalesAreRejected(String malformed) {
        ContractAssertions.assertFailsWithCode(
                () -> new LocaleTag(malformed), ContractErrorCodes.CONTRACT_INVALID_VALUE);
    }

    @Test
    void malformedTimezoneIsRejectedWithAStableCode() {
        String json = canonicalJson.writeString(validContext()).replace("Asia/Shanghai", "Not/AZone");

        ContractAssertions.assertFailsWithCode(
                () -> canonicalJson.read(json, TrustedExecutionContextRef.class),
                ContractErrorCodes.CONTRACT_INVALID_VALUE);
    }

    // Snapshot references must be non-blank and shaped like a stable code.
    @Test
    void snapshotReferenceIsValidated() {
        assertThat(validator.validate(validSnapshot())).isEmpty();

        assertViolationOn(new SnapshotRef("metadata", "metadata-17", ContractVersions.V1_3, contentHash()), "kind");
        assertViolationOn(new SnapshotRef("", "metadata-17", ContractVersions.V1_3, contentHash()), "kind");
        assertViolationOn(new SnapshotRef("METADATA", " ", ContractVersions.V1_3, contentHash()), "snapshotId");
        assertViolationOn(new SnapshotRef("METADATA", "metadata/17", ContractVersions.V1_3, contentHash()), "snapshotId");
        assertViolationOn(new SnapshotRef("METADATA", "metadata-17", null, contentHash()), "schemaVersion");
        assertViolationOn(new SnapshotRef("METADATA", "metadata-17", ContractVersions.V1_3, null), "contentHash");
    }

    @Test
    void snapshotReferenceBindsContentByHash() {
        String written = canonicalJson.writeString(validSnapshot());

        assertThat(written).isEqualTo("{\"contentHash\":\"" + contentHash().value() + "\","
                + "\"kind\":\"METADATA\",\"schemaVersion\":\"1.3\",\"snapshotId\":\"metadata-17\"}");
    }

    // Requirement 11: Plan references are validated without consulting the system clock.
    @Test
    void planReferenceIsValidated() {
        PlanRef plan = plan("plan-1", Instant.parse("2026-10-01T03:00:00Z"));

        assertThat(validator.validate(plan)).isEmpty();
        assertViolationOn(new PlanRef(" ", plan.planHash(), plan.expiresAt()), "planId");
        assertViolationOn(new PlanRef("plan/1", plan.planHash(), plan.expiresAt()), "planId");
        assertViolationOn(new PlanRef("plan-1", null, plan.expiresAt()), "planHash");
        assertViolationOn(new PlanRef("plan-1", plan.planHash(), null), "expiresAt");
    }

    @Test
    void alreadyExpiredPlanStillPassesValidationBecauseValidationNeverReadsTheSystemClock() {
        PlanRef longExpired = plan("plan-old", Instant.parse("2000-01-01T00:00:00Z"));

        assertThat(validator.validate(longExpired)).isEmpty();
    }

    // Requirement 13: expiry boundary is decided by the injected clock, not wall time.
    @Test
    void planExpiryBoundaryIsDecidedByTheInjectedClock() {
        Instant expiresAt = Instant.parse("2026-10-01T03:00:00Z");
        PlanRef plan = plan("plan-1", expiresAt);

        assertThat(plan.isExpired(Clock.fixed(expiresAt.minusNanos(1), ZoneOffset.UTC))).isFalse();
        assertThat(plan.isExpired(Clock.fixed(expiresAt, ZoneOffset.UTC))).isTrue();
        assertThat(plan.isExpired(Clock.fixed(expiresAt.plusSeconds(1), ZoneOffset.UTC))).isTrue();

        plan.requireNotExpired(Clock.fixed(expiresAt.minusNanos(1), ZoneOffset.UTC));
        ContractAssertions.assertFailsWithCode(
                () -> plan.requireNotExpired(Clock.fixed(expiresAt, ZoneOffset.UTC)),
                ContractErrorCodes.PLAN_EXPIRED);
    }

    @Test
    void planValidityDependsOnlyOnTheSuppliedClock() {
        Instant expiresAt = Instant.parse("2099-01-01T00:00:00Z");
        PlanRef plan = plan("plan-future", expiresAt);

        assertThat(plan.isExpired(Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC))).isFalse();
        assertThat(plan.isExpired(Clock.fixed(Instant.parse("2099-01-01T00:00:00Z"), ZoneOffset.UTC))).isTrue();
        assertThat(plan.isExpired(Clock.fixed(Instant.parse("2100-01-01T00:00:00Z"), ZoneOffset.UTC))).isTrue();
    }

    @Test
    void expiredPlanFailureCarriesThePlanAndTheObservedTime() {
        Instant expiresAt = Instant.parse("2026-10-01T03:00:00Z");
        PlanRef plan = plan("plan-1", expiresAt);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> plan.requireNotExpired(Clock.fixed(expiresAt.plusSeconds(60), ZoneOffset.UTC)));

        assertThat(thrown).isInstanceOf(PlanExpiredException.class);
        assertThat(((PlanExpiredException) thrown).details())
                .containsEntry("planId", "plan-1")
                .containsEntry("expiresAt", "2026-10-01T03:00:00Z")
                .containsEntry("evaluatedAt", "2026-10-01T03:01:00Z");
    }

    private static <T> void assertViolationOn(T bean, String propertyPath) {
        Set<ConstraintViolation<T>> violations = validator.validate(bean);
        assertThat(violations).as("expected a violation on %s", propertyPath).isNotEmpty();
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .as("violated paths")
                .contains(propertyPath);
    }

    private static ContentHash contentHash() {
        return ContentHash.sha256("snapshot".getBytes(StandardCharsets.UTF_8));
    }

    private static SnapshotRef validSnapshot() {
        return new SnapshotRef("METADATA", "metadata-17", ContractVersions.V1_3, contentHash());
    }

    private static PlanRef plan(String planId, Instant expiresAt) {
        return new PlanRef(planId, ContentHash.sha256("plan".getBytes(StandardCharsets.UTF_8)), expiresAt);
    }

    private static TrustedExecutionContextRef validContext() {
        return contextWith("none", "");
    }

    private static TrustedExecutionContextRef contextWith(String field, String value) {
        String tenantId = "tenant-001";
        String programId = "program-001";
        String userId = "user-001";
        String dataScopeId = "scope-008";
        String metadataSnapshotId = "metadata-17";
        String reportingCurrency = "CNY";

        switch (field) {
            case "tenantId" -> tenantId = value;
            case "programId" -> programId = value;
            case "userId" -> userId = value;
            case "dataScopeId" -> dataScopeId = value;
            case "metadataSnapshotId" -> metadataSnapshotId = value;
            case "reportingCurrency" -> reportingCurrency = value;
            case "none" -> {
                // keep the valid defaults
            }
            default -> throw new IllegalArgumentException("Unsupported override: " + field);
        }

        return new TrustedExecutionContextRef(
                tenantId,
                programId,
                userId,
                dataScopeId,
                metadataSnapshotId,
                LocaleTag.of(Locale.SIMPLIFIED_CHINESE),
                ZoneId.of("Asia/Shanghai"),
                reportingCurrency,
                AS_OF,
                WATERMARK);
    }
}