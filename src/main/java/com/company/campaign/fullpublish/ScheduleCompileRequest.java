package com.company.campaign.fullpublish;

import com.company.campaign.builder.capability.schedule.ScheduleBasicVersions;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.Objects;

/**
 * The explicit, frozen inputs of one deterministic schedule compile.
 *
 * <p>A request supplies only what the compile may read: the source component's stable logical identity, its
 * accepted capability code and contract version, its already-normalized payload, the exact source Manifest and
 * Journey hashes it descends from, and the exact schedule format, compiler and runtime schema versions. It
 * carries no repository, clock, UUID, random source, ambient thread-local state, model, network or provider
 * handle, so the compiler is a pure function of these explicit inputs.
 *
 * <p>The capability code, contract version, schedule format version, compiler version and runtime schema
 * version must be exactly the supported discriminators; any other value fails closed at construction rather
 * than being silently reinterpreted.
 */
public record ScheduleCompileRequest(
        String componentLogicalId,
        String capabilityCode,
        String contractVersion,
        CapabilityPayload payload,
        ContentHash sourceManifestHash,
        ContentHash journeyHash,
        String scheduleFormatVersion,
        String compilerVersion,
        String runtimeSchemaVersion) {

    public ScheduleCompileRequest {
        ScheduleSupport.requireIdentifier(componentLogicalId, "componentLogicalId");
        if (!ScheduleVersions.CAPABILITY_CODE.equals(capabilityCode)
                || !ScheduleVersions.CONTRACT_VERSION.equals(contractVersion)) {
            throw ScheduleSupport.invalid(
                    ScheduleErrorCodes.SCHEDULE_CAPABILITY_UNSUPPORTED, "capability");
        }
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(sourceManifestHash, "sourceManifestHash");
        Objects.requireNonNull(journeyHash, "journeyHash");
        if (!ScheduleVersions.SCHEDULE_FORMAT_VERSION.equals(scheduleFormatVersion)) {
            throw ScheduleSupport.invalid(
                    ScheduleErrorCodes.SCHEDULE_VERSION_UNSUPPORTED, "scheduleFormatVersion");
        }
        if (!ScheduleVersions.SCHEDULE_COMPILER_VERSION.equals(compilerVersion)) {
            throw ScheduleSupport.invalid(
                    ScheduleErrorCodes.SCHEDULE_COMPILER_VERSION_UNSUPPORTED, "compilerVersion");
        }
        if (!ScheduleVersions.RUNTIME_SCHEMA_VERSION.equals(runtimeSchemaVersion)) {
            throw ScheduleSupport.invalid(
                    ScheduleErrorCodes.SCHEDULE_VERSION_UNSUPPORTED, "runtimeSchemaVersion");
        }
    }

    /** The declared field names of one accepted {@code schedule.basic} payload, for the closed check. */
    public static java.util.List<String> declaredFields() {
        return java.util.List.of(
                ScheduleBasicVersions.FIELD_FORM,
                ScheduleBasicVersions.FIELD_ZONE_ID,
                ScheduleBasicVersions.FIELD_RUN_AT,
                ScheduleBasicVersions.FIELD_LOCAL_TIME,
                ScheduleBasicVersions.FIELD_START_DATE,
                ScheduleBasicVersions.FIELD_END_DATE);
    }

    @Override
    public String toString() {
        return "ScheduleCompileRequest[componentLogicalId=" + componentLogicalId
                + ", capability=" + capabilityCode + '/' + contractVersion
                + ", compilerVersion=" + compilerVersion + ']';
    }
}