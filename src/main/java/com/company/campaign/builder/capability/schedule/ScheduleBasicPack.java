package com.company.campaign.builder.capability.schedule;

import com.company.campaign.builder.capability.CapabilityPlanPreviewCard;
import com.company.campaign.builder.capability.pack.CapabilityEvaluationAsset;
import com.company.campaign.builder.capability.pack.CapabilityEvaluationFixture;
import com.company.campaign.builder.capability.pack.CapabilityFieldConstraint;
import com.company.campaign.builder.capability.pack.CapabilityFieldSchema;
import com.company.campaign.builder.capability.pack.CapabilityPackContract;
import com.company.campaign.builder.capability.pack.CapabilityPackMetadata;
import com.company.campaign.builder.capability.pack.CapabilityPayloadContract;
import com.company.campaign.builder.capability.pack.CapabilityValueKind;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CapabilityRequest;
import com.company.campaign.builder.compiler.CapabilityResolution;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.contracts.Issue;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The concrete, frozen {@code schedule.basic} capability pack at contract version 1.0.
 *
 * <p>It validates and deterministically normalizes exactly the three accepted schedule forms — {@code
 * IMMEDIATE} with a canonical timezone, {@code ONCE} with an offset instant that matches that canonical
 * timezone, and {@code DAILY} with a canonical local time, timezone and an inclusive, bounded calendar-date
 * window — and nothing else. Unknown fields, unknown forms, non-canonical timezones, mismatched offsets,
 * inverted or over-long windows and wrongly typed values all fail closed with one ordered, bounded issue that
 * never echoes the rejected value.
 *
 * <p>It deliberately does <em>not</em> generate occurrences, resolve a DST transition, create scheduling work,
 * compute an execution key or model pause/replacement behaviour: those are a later task's, and a resolution
 * here claims none of them. Its honest runtime compatibility therefore reports Preview as supported and
 * publication as unsupported.
 *
 * <p>The pack is a pure function of its request. It holds no repository, network, provider, model, clock,
 * random source or mutable registry, reads no catalog entry (it resolves no catalog reference), and produces
 * one normalized canonical payload plus exactly one bound generic capability preview card that carries only
 * already-safe machine facts.
 */
public final class ScheduleBasicPack implements CapabilityPackContract {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private static final CapabilityPackMetadata METADATA = buildMetadata();

    /** Every declared field name, required and optional, for the closed unknown-field check. */
    private static final Set<String> DECLARED_FIELDS = Set.of(
            ScheduleBasicVersions.FIELD_FORM,
            ScheduleBasicVersions.FIELD_ZONE_ID,
            ScheduleBasicVersions.FIELD_RUN_AT,
            ScheduleBasicVersions.FIELD_LOCAL_TIME,
            ScheduleBasicVersions.FIELD_START_DATE,
            ScheduleBasicVersions.FIELD_END_DATE);

    private static final List<String> RISK_CODES = List.of(
            ScheduleBasicVersions.RISK_PREVIEW_ONLY,
            ScheduleBasicVersions.RISK_OCCURRENCES_DEFERRED);

    private static final String FAILURE_MESSAGE =
            "The basic schedule capability resolution failed closed.";

    @Override
    public String capabilityCode() {
        return ScheduleBasicVersions.CAPABILITY_CODE;
    }

    @Override
    public String contractVersion() {
        return ScheduleBasicVersions.CONTRACT_VERSION;
    }

    @Override
    public CompositionSlot slot() {
        return ScheduleBasicVersions.SLOT;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public RuntimeCompatibility runtimeCompatibility() {
        return ScheduleBasicVersions.RUNTIME_COMPATIBILITY;
    }

    @Override
    public CapabilityPackMetadata packMetadata() {
        return METADATA;
    }

    // ------------------------------------------------------------------ resolution

    @Override
    public CapabilityResolution resolve(CapabilityRequest request) {
        if (request == null || request.payload() == null || request.logicalId() == null) {
            return failed(ScheduleBasicVersions.ISSUE_INPUT);
        }
        try {
            return resolveOrThrow(request);
        } catch (Rejected rejected) {
            // Every expected rejection is one ordered, bounded issue; the rejected value is never echoed.
            return failed(rejected.code);
        } catch (RuntimeException exception) {
            // A payload that is not a bounded canonical object, or any unexpected shape, fails closed.
            return failed(ScheduleBasicVersions.ISSUE_FIELD);
        }
    }

    private CapabilityResolution resolveOrThrow(CapabilityRequest request) {
        Map<String, Object> fields = request.payload().fields();
        requireKnownFields(fields);

        ScheduleBasicForm form = requireForm(fields);
        String zoneId = requireZoneId(fields);

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put(ScheduleBasicVersions.FIELD_FORM, form.name());
        normalized.put(ScheduleBasicVersions.FIELD_ZONE_ID, zoneId);

        switch (form) {
            case IMMEDIATE -> requireAbsent(fields, ScheduleBasicVersions.FIELD_RUN_AT,
                    ScheduleBasicVersions.FIELD_LOCAL_TIME, ScheduleBasicVersions.FIELD_START_DATE,
                    ScheduleBasicVersions.FIELD_END_DATE);
            case ONCE -> {
                String runAt = requireText(fields, ScheduleBasicVersions.FIELD_RUN_AT,
                        ScheduleBasicVersions.MAX_INSTANT_LENGTH);
                requireAbsent(fields, ScheduleBasicVersions.FIELD_LOCAL_TIME,
                        ScheduleBasicVersions.FIELD_START_DATE, ScheduleBasicVersions.FIELD_END_DATE);
                normalized.put(ScheduleBasicVersions.FIELD_RUN_AT, canonicalRunAt(runAt, zoneId));
            }
            case DAILY -> {
                String localTime = requireText(fields, ScheduleBasicVersions.FIELD_LOCAL_TIME,
                        ScheduleBasicVersions.MAX_LOCAL_TIME_LENGTH);
                String startDate = requireText(fields, ScheduleBasicVersions.FIELD_START_DATE,
                        ScheduleBasicVersions.MAX_LOCAL_TIME_LENGTH);
                String endDate = requireText(fields, ScheduleBasicVersions.FIELD_END_DATE,
                        ScheduleBasicVersions.MAX_LOCAL_TIME_LENGTH);
                requireAbsent(fields, ScheduleBasicVersions.FIELD_RUN_AT);
                String[] window = canonicalWindow(startDate, endDate);
                normalized.put(ScheduleBasicVersions.FIELD_LOCAL_TIME, canonicalLocalTime(localTime));
                normalized.put(ScheduleBasicVersions.FIELD_START_DATE, window[0]);
                normalized.put(ScheduleBasicVersions.FIELD_END_DATE, window[1]);
            }
            default -> throw new Rejected(ScheduleBasicVersions.ISSUE_FORM);
        }

        CapabilityPayload payload = CapabilityPayload.of(normalized);
        return new CapabilityResolution.Resolved(
                payload, List.of(), List.of(), List.of(card(request.logicalId(), payload, form)));
    }

    // ------------------------------------------------------------------ field shape

    private static void requireKnownFields(Map<String, Object> fields) {
        for (String name : fields.keySet()) {
            if (!DECLARED_FIELDS.contains(name)) {
                // An undeclared field fails closed: it is not silently ignored, nor carried into the payload.
                throw new Rejected(ScheduleBasicVersions.ISSUE_UNKNOWN_FIELD);
            }
        }
    }

    private static ScheduleBasicForm requireForm(Map<String, Object> fields) {
        String declared = requireText(fields, ScheduleBasicVersions.FIELD_FORM, 32);
        Optional<ScheduleBasicForm> form = ScheduleBasicForm.parse(declared);
        if (form.isEmpty()) {
            throw new Rejected(ScheduleBasicVersions.ISSUE_FORM);
        }
        return form.get();
    }

    private static String requireZoneId(Map<String, Object> fields) {
        String token = requireText(fields, ScheduleBasicVersions.FIELD_ZONE_ID,
                ScheduleBasicVersions.MAX_ZONE_ID_LENGTH);
        ZoneId zone;
        try {
            zone = ZoneId.of(token);
        } catch (DateTimeException exception) {
            throw new Rejected(ScheduleBasicVersions.ISSUE_ZONE);
        }
        // Only a canonical, region-based identifier is a timezone. A fixed offset ("+08:00", "Z"), a legacy
        // alias or a non-canonical spelling is refused rather than coerced into a neighbouring zone.
        if (zone instanceof ZoneOffset || !zone.getId().equals(token)) {
            throw new Rejected(ScheduleBasicVersions.ISSUE_ZONE);
        }
        return zone.getId();
    }

    /** The declared text of one field, required, bounded and of the exact string type. */
    private static String requireText(Map<String, Object> fields, String name, int maxLength) {
        Object value = fields.get(name);
        if (!(value instanceof String text) || text.isEmpty() || text.length() > maxLength) {
            throw new Rejected(ScheduleBasicVersions.ISSUE_FIELD);
        }
        return text;
    }

    private static void requireAbsent(Map<String, Object> fields, String... names) {
        for (String name : names) {
            if (fields.containsKey(name)) {
                // A field that the declared form does not define is a contradictory payload, not an extra.
                throw new Rejected(ScheduleBasicVersions.ISSUE_FIELD);
            }
        }
    }

    // ------------------------------------------------------------------ deterministic normalization

    /**
     * The canonical offset-instant text of a {@code ONCE} schedule.
     *
     * <p>The declared offset must equal the canonical timezone's offset at that instant. That is a pure
     * zone-rules lookup on one explicit instant — it computes no occurrence, no DST transition and no
     * scheduling work; it only refuses an instant that contradicts the timezone it claims to be in.
     */
    private static String canonicalRunAt(String token, String zoneId) {
        OffsetDateTime parsed;
        try {
            parsed = OffsetDateTime.parse(token);
        } catch (DateTimeParseException exception) {
            throw new Rejected(ScheduleBasicVersions.ISSUE_FIELD);
        }
        ZoneId zone = ZoneId.of(zoneId);
        if (!zone.getRules().getOffset(parsed.toInstant()).equals(parsed.getOffset())) {
            throw new Rejected(ScheduleBasicVersions.ISSUE_WINDOW);
        }
        return parsed.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /** The canonical local-time text of a {@code DAILY} schedule. */
    private static String canonicalLocalTime(String token) {
        LocalTime parsed;
        try {
            parsed = LocalTime.parse(token);
        } catch (DateTimeParseException exception) {
            throw new Rejected(ScheduleBasicVersions.ISSUE_FIELD);
        }
        return parsed.toString();
    }

    /**
     * The canonical inclusive calendar-date window of a {@code DAILY} schedule, counted in calendar days.
     *
     * <p>The count is an inclusive day difference, not occurrence generation: it never expands a date into
     * occurrences, skips a DST transition or produces work.
     */
    private static String[] canonicalWindow(String startToken, String endToken) {
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(startToken);
            end = LocalDate.parse(endToken);
        } catch (DateTimeParseException exception) {
            throw new Rejected(ScheduleBasicVersions.ISSUE_FIELD);
        }
        if (end.isBefore(start)) {
            throw new Rejected(ScheduleBasicVersions.ISSUE_WINDOW);
        }
        long inclusiveDays = ChronoUnit.DAYS.between(start, end) + 1;
        if (inclusiveDays > ScheduleBasicVersions.MAX_CALENDAR_DAYS) {
            throw new Rejected(ScheduleBasicVersions.ISSUE_WINDOW);
        }
        return new String[] {start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                end.format(DateTimeFormatter.ISO_LOCAL_DATE)};
    }

    // ------------------------------------------------------------------ safe preview card

    private static CapabilityPlanPreviewCard card(
            String logicalId, CapabilityPayload payload, ScheduleBasicForm form) {
        return new CapabilityPlanPreviewCard(
                CapabilityPlanPreviewCard.CAPABILITY_PREVIEW,
                CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION,
                logicalId,
                ScheduleBasicVersions.CAPABILITY_CODE,
                ScheduleBasicVersions.CONTRACT_VERSION,
                payload.contentHash(),
                // This capability resolves no catalog reference, so the optional slot is genuinely absent.
                null,
                ScheduleBasicVersions.RUNTIME_COMPATIBILITY,
                List.of(form.summaryCode(), ScheduleBasicVersions.SUMMARY_CONTRACT_ONLY),
                RISK_CODES,
                METADATA.evaluationAsset().contentHash());
    }

    // ------------------------------------------------------------------ frozen metadata

    private static CapabilityPackMetadata buildMetadata() {
        CapabilityPayloadContract payloadContract = new CapabilityPayloadContract(
                List.of(
                        new CapabilityFieldSchema(ScheduleBasicVersions.FIELD_FORM,
                                new CapabilityFieldConstraint.EnumCodes(ScheduleBasicForm.codes())),
                        new CapabilityFieldSchema(ScheduleBasicVersions.FIELD_ZONE_ID,
                                new CapabilityFieldConstraint.Text(ScheduleBasicVersions.MAX_ZONE_ID_LENGTH))),
                List.of(
                        new CapabilityFieldSchema(ScheduleBasicVersions.FIELD_RUN_AT,
                                new CapabilityFieldConstraint.Text(ScheduleBasicVersions.MAX_INSTANT_LENGTH)),
                        new CapabilityFieldSchema(ScheduleBasicVersions.FIELD_LOCAL_TIME,
                                new CapabilityFieldConstraint.Text(ScheduleBasicVersions.MAX_LOCAL_TIME_LENGTH)),
                        new CapabilityFieldSchema(ScheduleBasicVersions.FIELD_START_DATE,
                                CapabilityFieldConstraint.unconstrained(CapabilityValueKind.DATE)),
                        new CapabilityFieldSchema(ScheduleBasicVersions.FIELD_END_DATE,
                                CapabilityFieldConstraint.unconstrained(CapabilityValueKind.DATE))));
        return CapabilityPackMetadata.of(
                ScheduleBasicVersions.CAPABILITY_CODE,
                ScheduleBasicVersions.PACK_VERSION,
                ScheduleBasicVersions.CONTRACT_VERSION,
                ScheduleBasicVersions.SCHEMA_VERSION,
                CapabilityPlanPreviewCard.CAPABILITY_PREVIEW,
                CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION,
                ScheduleBasicVersions.SLOT,
                ScheduleBasicVersions.RUNTIME_COMPATIBILITY,
                payloadContract,
                evaluationAsset());
    }

    /**
     * The versioned evaluation asset: its positive and negative fixtures pin the canonical hash of an exact
     * payload body, so the two sets can never blur into one undifferentiated corpus.
     */
    private static CapabilityEvaluationAsset evaluationAsset() {
        List<CapabilityEvaluationFixture> positive = List.of(
                fixture("schedule-immediate-valid", CapabilityEvaluationFixture.Expectation.ACCEPTED,
                        Map.of(ScheduleBasicVersions.FIELD_FORM, "IMMEDIATE",
                                ScheduleBasicVersions.FIELD_ZONE_ID, "Asia/Shanghai")),
                fixture("schedule-once-valid", CapabilityEvaluationFixture.Expectation.ACCEPTED,
                        Map.of(ScheduleBasicVersions.FIELD_FORM, "ONCE",
                                ScheduleBasicVersions.FIELD_ZONE_ID, "Asia/Shanghai",
                                ScheduleBasicVersions.FIELD_RUN_AT, "2026-11-01T09:00:00+08:00")),
                fixture("schedule-daily-valid", CapabilityEvaluationFixture.Expectation.ACCEPTED,
                        Map.of(ScheduleBasicVersions.FIELD_FORM, "DAILY",
                                ScheduleBasicVersions.FIELD_ZONE_ID, "Asia/Shanghai",
                                ScheduleBasicVersions.FIELD_LOCAL_TIME, "09:00",
                                ScheduleBasicVersions.FIELD_START_DATE, "2026-11-01",
                                ScheduleBasicVersions.FIELD_END_DATE, "2026-11-30")));
        List<CapabilityEvaluationFixture> negative = List.of(
                fixture("schedule-bad-zone", CapabilityEvaluationFixture.Expectation.REJECTED,
                        Map.of(ScheduleBasicVersions.FIELD_FORM, "IMMEDIATE",
                                ScheduleBasicVersions.FIELD_ZONE_ID, "Not/AZone")),
                fixture("schedule-unknown-field", CapabilityEvaluationFixture.Expectation.REJECTED,
                        Map.of(ScheduleBasicVersions.FIELD_FORM, "IMMEDIATE",
                                ScheduleBasicVersions.FIELD_ZONE_ID, "Asia/Shanghai",
                                "unexpectedSetting", "x")),
                fixture("schedule-window-too-long", CapabilityEvaluationFixture.Expectation.REJECTED,
                        Map.of(ScheduleBasicVersions.FIELD_FORM, "DAILY",
                                ScheduleBasicVersions.FIELD_ZONE_ID, "Asia/Shanghai",
                                ScheduleBasicVersions.FIELD_LOCAL_TIME, "09:00",
                                ScheduleBasicVersions.FIELD_START_DATE, "2026-01-01",
                                ScheduleBasicVersions.FIELD_END_DATE, "2026-12-31")));
        ContentHash hash = assetHash(positive, negative);
        return new CapabilityEvaluationAsset(
                ScheduleBasicVersions.EVALUATION_ASSET_ID,
                ScheduleBasicVersions.EVALUATION_ASSET_VERSION,
                hash, positive, negative);
    }

    private static CapabilityEvaluationFixture fixture(
            String fixtureId, CapabilityEvaluationFixture.Expectation expectation, Map<String, Object> body) {
        return new CapabilityEvaluationFixture(
                fixtureId, expectation, CapabilityPayload.of(body).contentHash());
    }

    /** The canonical asset hash: a deterministic function of the asset identity and of every pinned fixture. */
    private static ContentHash assetHash(
            List<CapabilityEvaluationFixture> positive, List<CapabilityEvaluationFixture> negative) {
        List<String> material = new ArrayList<>();
        material.add(ScheduleBasicVersions.EVALUATION_ASSET_ID);
        material.add(ScheduleBasicVersions.EVALUATION_ASSET_VERSION);
        for (CapabilityEvaluationFixture fixture : positive) {
            material.add("+" + fixture.fixtureId() + '|' + fixture.expectation() + '|'
                    + fixture.payloadHash().value());
        }
        for (CapabilityEvaluationFixture fixture : negative) {
            material.add("-" + fixture.fixtureId() + '|' + fixture.expectation() + '|'
                    + fixture.payloadHash().value());
        }
        return CANONICAL_JSON.hash(List.copyOf(material));
    }

    // ------------------------------------------------------------------ failure shape

    private static CapabilityResolution failed(String code) {
        return new CapabilityResolution.Failed(List.of(Issue.error(code, FAILURE_MESSAGE)));
    }

    /** One expected rejection, carrying its stable issue code and never the rejected value. */
    private static final class Rejected extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient String code;

        private Rejected(String code) {
            super(null, null, false, false);
            this.code = code;
        }
    }

    /** Redacted diagnostics: this pack holds no payload value, so it prints only its frozen identity. */
    @Override
    public String toString() {
        return "ScheduleBasicPack[capabilityCode=" + ScheduleBasicVersions.CAPABILITY_CODE
                + ", contractVersion=" + ScheduleBasicVersions.CONTRACT_VERSION + ']';
    }
}
