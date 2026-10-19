package com.company.campaign.builder.capability.schedule;

import com.company.campaign.builder.capability.CapabilityPlanPreviewCard;
import com.company.campaign.builder.capability.pack.CapabilityEvaluationAsset;
import com.company.campaign.builder.capability.pack.CapabilityEvaluationFixture;
import com.company.campaign.builder.capability.pack.CapabilityFieldConstraint;
import com.company.campaign.builder.capability.pack.CapabilityFieldSchema;
import com.company.campaign.builder.capability.pack.CapabilityPackMetadata;
import com.company.campaign.builder.capability.pack.CapabilityPayloadContract;
import com.company.campaign.builder.capability.pack.CapabilityValueKind;
import com.company.campaign.builder.compiler.CapabilityContract;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CapabilityRequest;
import com.company.campaign.builder.compiler.CapabilityResolution;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.contracts.Issue;
import com.company.campaign.contracts.SchemaVersion;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * T19: the concrete {@code schedule.basic} capability pack at contract version 1.0.
 *
 * <p>It pins the three accepted forms and their deterministic normalization, the frozen metadata and payload
 * contract, the honest preview-only runtime compatibility and the safe preview card, and proves that unknown
 * fields, unknown forms, non-canonical timezones, mismatched offsets and out-of-bound windows all fail closed.
 * It generates no occurrence, resolves no DST transition and computes no execution key — none of those exists
 * in this contract.
 */
class ScheduleBasicPackTest {

    private static final ScheduleBasicPack PACK = new ScheduleBasicPack();
    private static final String LOGICAL_ID = "schedule-main";

    // ------------------------------------------------------------------ frozen metadata

    @Test
    void thePackDeclaresItsExactFrozenMetadata() {
        CapabilityPackMetadata metadata = PACK.packMetadata();

        assertThat(CapabilityContract.class.isAssignableFrom(ScheduleBasicPack.class)).isTrue();
        assertThat(PACK.capabilityCode()).isEqualTo("schedule.basic");
        assertThat(PACK.contractVersion()).isEqualTo("1.0");
        assertThat(PACK.slot()).isEqualTo(new CompositionSlot("campaign.schedule", false, true));
        assertThat(PACK.enabled()).isTrue();
        assertThat(metadata.capabilityCode()).isEqualTo("schedule.basic");
        assertThat(metadata.packVersion()).isEqualTo("t19-v1");
        assertThat(metadata.contractVersion()).isEqualTo("1.0");
        assertThat(metadata.schemaVersion()).isEqualTo(SchemaVersion.parse("1.0"));
        assertThat(metadata.slot()).isEqualTo(new CompositionSlot("campaign.schedule", false, true));
        assertThat(metadata.previewCardKind()).isEqualTo(CapabilityPlanPreviewCard.CAPABILITY_PREVIEW);
        assertThat(metadata.previewCardVersion()).isEqualTo(CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION);
        assertThatCode(metadata::requireValidPackHash).doesNotThrowAnyException();
    }

    @Test
    void theRuntimeCompatibilityIsHonestlyPreviewOnly() {
        RuntimeCompatibility compatibility = PACK.runtimeCompatibility();

        assertThat(compatibility.runtimeSchemaVersion()).isEqualTo("campaign-runtime/1.0");
        assertThat(compatibility.previewSupported()).isTrue();
        // T20-T23 are not implemented: this pack must never claim production runtime support.
        assertThat(compatibility.publishable()).isFalse();
        assertThat(PACK.packMetadata().runtimeCompatibility()).isEqualTo(compatibility);
    }

    @Test
    void theFrozenPayloadContractDeclaresExactlyTheThreeFormsAndTheirFields() {
        CapabilityPayloadContract contract = PACK.packMetadata().payloadContract();

        assertThat(contract.requiredNames()).containsExactlyInAnyOrder("form", "zoneId");
        assertThat(contract.optionalNames()).containsExactlyInAnyOrder(
                "runAt", "localTime", "startDate", "endDate");
        assertThat(constraintOf(contract, "form"))
                .isEqualTo(new CapabilityFieldConstraint.EnumCodes(ScheduleBasicForm.codes()));
        assertThat(constraintOf(contract, "zoneId"))
                .isEqualTo(new CapabilityFieldConstraint.Text(ScheduleBasicVersions.MAX_ZONE_ID_LENGTH));
        assertThat(kindOf(contract, "startDate")).isEqualTo(CapabilityValueKind.DATE);
        assertThat(kindOf(contract, "endDate")).isEqualTo(CapabilityValueKind.DATE);
        assertThat(ScheduleBasicForm.codes()).containsExactly("IMMEDIATE", "ONCE", "DAILY");
    }

    // ------------------------------------------------------------------ deterministic normalization

    @Test
    void immediateNormalizesToItsFormAndCanonicalTimezone() {
        CapabilityResolution.Resolved resolved = accepted(Map.of("form", "IMMEDIATE", "zoneId", "Asia/Shanghai"));

        assertThat(resolved.value().fields())
                .containsExactlyInAnyOrderEntriesOf(Map.of("form", "IMMEDIATE", "zoneId", "Asia/Shanghai"));
        // Nothing about occurrences, DST or work is added: the normalized payload is exactly the declaration.
        assertThat(resolved.value().fields()).hasSize(2);
        assertThat(resolved.references()).isEmpty();
        assertThat(resolved.cards()).isEmpty();
        assertThat(resolved.capabilityPreviewCards()).hasSize(1);
    }

    @Test
    void onceNormalizesItsOffsetInstantAndKeepsTheDeclaredInstant() {
        CapabilityResolution.Resolved resolved = accepted(Map.of(
                "form", "ONCE", "zoneId", "Asia/Shanghai", "runAt", "2026-11-01T09:00:00+08:00"));

        assertThat(resolved.value().fields()).containsEntry("form", "ONCE")
                .containsEntry("zoneId", "Asia/Shanghai");
        Object runAt = resolved.value().fields().get("runAt");
        assertThat(runAt).isInstanceOf(String.class);
        // The canonical text denotes exactly the declared instant: normalization re-spells, never re-times.
        assertThat(OffsetDateTime.parse((String) runAt).toInstant())
                .isEqualTo(Instant.parse("2026-11-01T01:00:00Z"));
    }

    @Test
    void dailyNormalizesItsLocalTimeAndInclusiveWindow() {
        CapabilityResolution.Resolved resolved = accepted(Map.of(
                "form", "DAILY", "zoneId", "Asia/Shanghai", "localTime", "09:00",
                "startDate", "2026-11-01", "endDate", "2026-11-30"));

        assertThat(resolved.value().fields()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "form", "DAILY", "zoneId", "Asia/Shanghai", "localTime", "09:00",
                "startDate", "2026-11-01", "endDate", "2026-11-30"));
    }

    @Test
    void normalizationIsDeterministicAcrossEquivalentSpellings() {
        CapabilityResolution.Resolved once = accepted(Map.of(
                "form", "ONCE", "zoneId", "Asia/Shanghai", "runAt", "2026-11-01T09:00:00+08:00"));
        CapabilityResolution.Resolved onceShort = accepted(Map.of(
                "form", "ONCE", "zoneId", "Asia/Shanghai", "runAt", "2026-11-01T09:00+08:00"));
        CapabilityResolution.Resolved daily = accepted(Map.of(
                "form", "DAILY", "zoneId", "Asia/Shanghai", "localTime", "09:00:00",
                "startDate", "2026-11-01", "endDate", "2026-11-30"));
        CapabilityResolution.Resolved dailyShort = accepted(Map.of(
                "form", "DAILY", "zoneId", "Asia/Shanghai", "localTime", "09:00",
                "startDate", "2026-11-01", "endDate", "2026-11-30"));

        assertThat(once.value().contentHash()).isEqualTo(onceShort.value().contentHash());
        assertThat(daily.value().contentHash()).isEqualTo(dailyShort.value().contentHash());
        // Resolving the same request twice yields byte-identical material.
        assertThat(accepted(Map.of("form", "IMMEDIATE", "zoneId", "Asia/Shanghai")).value().canonicalText())
                .isEqualTo(accepted(Map.of("form", "IMMEDIATE", "zoneId", "Asia/Shanghai")).value().canonicalText());
    }

    // ------------------------------------------------------------------ the safe preview card

    @Test
    void theResolvedComponentCarriesExactlyOneBoundSafePreviewCard() {
        CapabilityResolution.Resolved resolved = accepted(Map.of(
                "form", "DAILY", "zoneId", "Asia/Shanghai", "localTime", "09:00",
                "startDate", "2026-11-01", "endDate", "2026-11-30"));
        CapabilityPlanPreviewCard card = resolved.capabilityPreviewCards().get(0);

        assertThat(card.cardKind()).isEqualTo(CapabilityPlanPreviewCard.CAPABILITY_PREVIEW);
        assertThat(card.cardContractVersion()).isEqualTo(CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION);
        assertThat(card.componentLogicalId()).isEqualTo(LOGICAL_ID);
        assertThat(card.capabilityCode()).isEqualTo("schedule.basic");
        assertThat(card.contractVersion()).isEqualTo("1.0");
        assertThat(card.normalizedPayloadHash()).isEqualTo(resolved.value().contentHash());
        // This capability resolves no catalog reference, so the optional slot is genuinely absent.
        assertThat(card.resolvedCatalogReferenceHash()).isNull();
        assertThat(card.hasResolvedCatalogReference()).isFalse();
        assertThat(card.runtimeCompatibility()).isEqualTo(PACK.runtimeCompatibility());
        assertThat(card.evaluationAssetHash())
                .isEqualTo(PACK.packMetadata().evaluationAsset().contentHash());
        assertThat(card.summaryCodes()).contains("SCHEDULE_DAILY", ScheduleBasicVersions.SUMMARY_CONTRACT_ONLY);
        assertThat(card.riskCodes()).containsExactlyInAnyOrder(
                ScheduleBasicVersions.RISK_PREVIEW_ONLY, ScheduleBasicVersions.RISK_OCCURRENCES_DEFERRED);
    }

    // ------------------------------------------------------------------ fail closed

    @Test
    void anUnknownFieldFailsClosed() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("form", "IMMEDIATE");
        body.put("zoneId", "Asia/Shanghai");
        body.put("unexpectedSetting", "x");

        assertRejected(body, ScheduleBasicVersions.ISSUE_UNKNOWN_FIELD);
    }

    @Test
    void aMissingRequiredFieldFailsClosed() {
        assertRejected(Map.of("form", "IMMEDIATE"), ScheduleBasicVersions.ISSUE_FIELD);
        assertRejected(Map.of("zoneId", "Asia/Shanghai"), ScheduleBasicVersions.ISSUE_FIELD);
    }

    @Test
    void aWronglyTypedFieldFailsClosed() {
        assertRejected(Map.of("form", 1, "zoneId", "Asia/Shanghai"), ScheduleBasicVersions.ISSUE_FIELD);
        assertRejected(Map.of("form", "IMMEDIATE", "zoneId", 8), ScheduleBasicVersions.ISSUE_FIELD);
        assertRejected(Map.of("form", "ONCE", "zoneId", "Asia/Shanghai", "runAt", 20261101),
                ScheduleBasicVersions.ISSUE_FIELD);
    }

    @Test
    void anUnsupportedFormFailsClosed() {
        assertRejected(Map.of("form", "HOURLY", "zoneId", "Asia/Shanghai"),
                ScheduleBasicVersions.ISSUE_FORM);
        assertRejected(Map.of("form", "immediate", "zoneId", "Asia/Shanghai"),
                ScheduleBasicVersions.ISSUE_FORM);
    }

    @Test
    void aNonCanonicalTimezoneFailsClosed() {
        assertRejected(Map.of("form", "IMMEDIATE", "zoneId", "Not/AZone"), ScheduleBasicVersions.ISSUE_ZONE);
        assertRejected(Map.of("form", "IMMEDIATE", "zoneId", "+08:00"), ScheduleBasicVersions.ISSUE_ZONE);
        assertRejected(Map.of("form", "IMMEDIATE", "zoneId", "Z"), ScheduleBasicVersions.ISSUE_ZONE);
    }

    @Test
    void anInstantThatContradictsItsTimezoneFailsClosed() {
        assertRejected(Map.of("form", "ONCE", "zoneId", "Asia/Shanghai",
                "runAt", "2026-11-01T09:00:00Z"), ScheduleBasicVersions.ISSUE_WINDOW);
        assertRejected(Map.of("form", "ONCE", "zoneId", "Asia/Shanghai",
                "runAt", "not-an-instant"), ScheduleBasicVersions.ISSUE_FIELD);
    }

    @Test
    void anInvertedOrOverLongDailyWindowFailsClosed() {
        assertRejected(Map.of("form", "DAILY", "zoneId", "Asia/Shanghai", "localTime", "09:00",
                "startDate", "2026-11-30", "endDate", "2026-11-01"), ScheduleBasicVersions.ISSUE_WINDOW);
        assertRejected(Map.of("form", "DAILY", "zoneId", "Asia/Shanghai", "localTime", "09:00",
                "startDate", "2026-01-01", "endDate", "2026-12-31"), ScheduleBasicVersions.ISSUE_WINDOW);
        // The inclusive bound itself is accepted: exactly 90 calendar days.
        accepted(Map.of("form", "DAILY", "zoneId", "Asia/Shanghai", "localTime", "09:00",
                "startDate", "2026-11-01", "endDate", "2027-01-29"));
    }

    @Test
    void aFieldTheDeclaredFormDoesNotDefineFailsClosed() {
        assertRejected(Map.of("form", "IMMEDIATE", "zoneId", "Asia/Shanghai", "runAt",
                "2026-11-01T09:00:00+08:00"), ScheduleBasicVersions.ISSUE_FIELD);
        assertRejected(Map.of("form", "ONCE", "zoneId", "Asia/Shanghai", "runAt",
                "2026-11-01T09:00:00+08:00", "localTime", "09:00"), ScheduleBasicVersions.ISSUE_FIELD);
    }

    @Test
    void aMissingHostInputFailsClosed() {
        assertThat(((CapabilityResolution.Failed) PACK.resolve(null)).issues()).hasSize(1);
        assertThat(((CapabilityResolution.Failed) PACK.resolve(null)).issues().get(0).code())
                .isEqualTo(ScheduleBasicVersions.ISSUE_INPUT);
    }

    // ------------------------------------------------------------------ the versioned evaluation asset

    @Test
    void everyPinnedFixtureHashMatchesARealPayloadAndItsDeclaredPolarity() {
        CapabilityEvaluationAsset asset = PACK.packMetadata().evaluationAsset();

        assertThat(asset.assetId()).isEqualTo("t19-schedule-basic-evaluation-v1");
        assertThat(asset.assetVersion()).isEqualTo("1.0");
        assertThat(asset.positiveFixtures()).isNotEmpty();
        assertThat(asset.negativeFixtures()).isNotEmpty();

        Map<String, Map<String, Object>> bodies = fixtureBodies();
        for (CapabilityEvaluationFixture fixture : asset.allFixtures()) {
            Map<String, Object> body = bodies.get(fixture.fixtureId());
            assertThat(body).as("fixture %s", fixture.fixtureId()).isNotNull();
            assertThat(CapabilityPayload.of(body).contentHash())
                    .as("fixture %s", fixture.fixtureId())
                    .isEqualTo(fixture.payloadHash());
            boolean accepted = resolve(body) instanceof CapabilityResolution.Resolved;
            assertThat(accepted).as("fixture %s", fixture.fixtureId()).isEqualTo(fixture.isPositive());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Map<String, Object>> fixtureBodies() {
        Map<String, Map<String, Object>> bodies = new LinkedHashMap<>();
        bodies.put("schedule-immediate-valid", Map.of("form", "IMMEDIATE", "zoneId", "Asia/Shanghai"));
        bodies.put("schedule-once-valid", Map.of("form", "ONCE", "zoneId", "Asia/Shanghai",
                "runAt", "2026-11-01T09:00:00+08:00"));
        bodies.put("schedule-daily-valid", Map.of("form", "DAILY", "zoneId", "Asia/Shanghai",
                "localTime", "09:00", "startDate", "2026-11-01", "endDate", "2026-11-30"));
        bodies.put("schedule-bad-zone", Map.of("form", "IMMEDIATE", "zoneId", "Not/AZone"));
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("form", "IMMEDIATE");
        unknown.put("zoneId", "Asia/Shanghai");
        unknown.put("unexpectedSetting", "x");
        bodies.put("schedule-unknown-field", unknown);
        bodies.put("schedule-window-too-long", Map.of("form", "DAILY", "zoneId", "Asia/Shanghai",
                "localTime", "09:00", "startDate", "2026-01-01", "endDate", "2026-12-31"));
        return bodies;
    }

    private static CapabilityResolution resolve(Map<String, Object> body) {
        return PACK.resolve(new CapabilityRequest(
                LOGICAL_ID, CapabilityPayload.of(body),
                BuilderFixtures.catalog(PACK), BuilderFixtures.policy(), BuilderFixtures.context()));
    }

    private static CapabilityResolution.Resolved accepted(Map<String, Object> body) {
        CapabilityResolution resolution = resolve(body);
        assertThat(resolution).isInstanceOf(CapabilityResolution.Resolved.class);
        return (CapabilityResolution.Resolved) resolution;
    }

    private static void assertRejected(Map<String, Object> body, String code) {
        CapabilityResolution resolution = resolve(body);
        assertThat(resolution).isInstanceOf(CapabilityResolution.Failed.class);
        List<Issue> issues = ((CapabilityResolution.Failed) resolution).issues();
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).code()).isEqualTo(code);
    }

    private static CapabilityFieldConstraint constraintOf(CapabilityPayloadContract contract, String field) {
        for (CapabilityFieldSchema schema : contract.fields()) {
            if (schema.fieldName().equals(field)) {
                return schema.constraint();
            }
        }
        throw new AssertionError("undeclared field " + field);
    }

    private static CapabilityValueKind kindOf(CapabilityPayloadContract contract, String field) {
        return constraintOf(contract, field).kind();
    }
}