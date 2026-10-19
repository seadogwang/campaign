package com.company.campaign.builder.skill;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.contracts.SchemaVersionException;
import com.company.campaign.security.data.DataClassification;

import java.util.ArrayList;
import java.util.List;

/**
 * Strict, casing-exact parsing of the closed vocabularies of a Segment Skill resource.
 *
 * <p>Every parse is exact: {@code "experimental"}, {@code "Resolved"} and {@code "ready_for_preview"} are
 * refused, because a Skill is a versioned contract and one meaning must have one spelling. Where a text only
 * differs by case from a known value the rejection is reported as a casing failure so an author sees the
 * precise mistake, while a genuinely unknown value is reported as unsupported.
 */
final class SegmentSkillWire {

    private SegmentSkillWire() {
    }

    static SchemaVersion parseSchemaVersion(String value) {
        if (value == null) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.MISSING_FIELD, "A Skill resource requires a schema version");
        }
        try {
            return SchemaVersion.parse(value);
        } catch (SchemaVersionException failure) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.VERSION_UNSUPPORTED, "A Skill schema version is malformed");
        }
    }

    static SegmentCapabilityState parseCapabilityState(String value) {
        if (value == null) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.MISSING_FIELD, "A Skill resource requires a capability state");
        }
        for (SegmentCapabilityState state : SegmentCapabilityState.values()) {
            if (state.name().equals(value)) {
                return state;
            }
        }
        for (SegmentCapabilityState state : SegmentCapabilityState.values()) {
            if (state.name().equalsIgnoreCase(value)) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.CASING_INVALID, "A Skill capability state has an invalid casing");
            }
        }
        throw SegmentSkillException.invalid(
                SegmentSkillErrorCodes.CAPABILITY_STATE_UNSUPPORTED, "A Skill capability state is unknown");
    }

    static List<SegmentSkillDefinition.ToolPin> tools(List<SegmentSkillResource.ToolResource> tools) {
        if (tools == null || tools.isEmpty()) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.MISSING_FIELD, "A Skill resource requires an ordered Tool allowlist");
        }
        List<SegmentSkillDefinition.ToolPin> pins = new ArrayList<>(tools.size());
        for (int index = 0; index < tools.size(); index++) {
            SegmentSkillResource.ToolResource tool = tools.get(index);
            if (tool == null) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.NULL_FIELD, "A Skill Tool entry must not be null");
            }
            if (tool.toolId() == null || tool.toolVersion() == null) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.MISSING_FIELD, "A Skill Tool entry requires its identifier and version");
            }
            if (tool.maxPayloadBytes() == null) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.MISSING_FIELD, "A Skill Tool entry requires its payload bound");
            }
            pins.add(new SegmentSkillDefinition.ToolPin(
                    tool.toolId(), tool.toolVersion(), index, tool.maxPayloadBytes()));
        }
        return pins;
    }

    static List<Disposition> dispositions(List<String> values, String field) {
        if (values == null || values.isEmpty()) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.MISSING_FIELD, "A Skill resource requires dispositions: " + field);
        }
        List<Disposition> dispositions = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.NULL_FIELD, "A Skill disposition must not be null: " + field);
            }
            dispositions.add(parseDisposition(value, field));
        }
        return dispositions;
    }

    private static Disposition parseDisposition(String value, String field) {
        for (Disposition disposition : Disposition.values()) {
            if (disposition.name().equals(value)) {
                return disposition;
            }
        }
        for (Disposition disposition : Disposition.values()) {
            if (disposition.name().equalsIgnoreCase(value)) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.CASING_INVALID, "A Skill disposition has an invalid casing: " + field);
            }
        }
        throw SegmentSkillException.invalid(
                SegmentSkillErrorCodes.DISPOSITION_UNSUPPORTED, "A Skill disposition is unknown: " + field);
    }

    static List<String> terminalStates(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.MISSING_FIELD, "A Skill resource requires terminal states");
        }
        List<String> states = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.NULL_FIELD, "A Skill terminal state must not be null");
            }
            if (SegmentSkillTerminalStates.isTerminal(value)) {
                states.add(value);
                continue;
            }
            for (String known : SegmentSkillTerminalStates.ordered()) {
                if (known.equalsIgnoreCase(value)) {
                    throw SegmentSkillException.invalid(
                            SegmentSkillErrorCodes.CASING_INVALID, "A Skill terminal state has an invalid casing");
                }
            }
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.TERMINAL_STATE_UNSUPPORTED, "A Skill terminal state is unknown");
        }
        return states;
    }

    static DataClassification dataClassification(String value) {
        if (value == null) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.MISSING_FIELD, "A Skill resource requires a data classification");
        }
        for (DataClassification classification : DataClassification.values()) {
            if (classification.name().equals(value)) {
                return classification;
            }
        }
        for (DataClassification classification : DataClassification.values()) {
            if (classification.name().equalsIgnoreCase(value)) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.CASING_INVALID, "A Skill data classification has an invalid casing");
            }
        }
        throw SegmentSkillException.invalid(
                SegmentSkillErrorCodes.DATA_CLASSIFICATION_UNSAFE, "A Skill data classification is unknown");
    }
}