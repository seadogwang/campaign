package com.company.campaign.builder.skill;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * The external authoring form of one Segment Skill resource.
 *
 * <p>Every field is nullable on the wire so the loader can distinguish "absent" from "present" and report the
 * exact bounded failure instead of relying on a binder default. It is the only shape the strict loader binds;
 * {@link #toDefinition()} converts it into the validated, hash-verified value model.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SegmentSkillResource(
        String schemaVersion,
        String skillId,
        String skillVersion,
        String capabilityState,
        String evidenceClass,
        ContractPinsResource contractPins,
        List<ToolResource> tools,
        List<String> inputDispositions,
        List<String> outputDispositions,
        List<String> terminalStates,
        LimitsResource limits,
        String dataClassification,
        Boolean sampleOutputPermitted,
        String ambiguityStopsAt,
        Map<String, String> failureBehavior
) {

    /** The wire form of the pinned contract versions. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ContractPinsResource(
            String agentContractVersion,
            String intentVersion,
            String dslVersion,
            String canonicalizerVersion,
            String resolverVersion,
            String validatorVersion,
            String plannerVersion,
            String previewContractVersion,
            String toolSchemaVersion
    ) {
    }

    /** The wire form of one ordered Tool pin. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ToolResource(String toolId, String toolVersion, Integer maxPayloadBytes) {
    }

    /** The wire form of the bounded limits. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record LimitsResource(
            Integer maxSteps,
            Integer maxToolCalls,
            Integer maxToolPayloadBytes,
            Integer maxModelOutputBytes,
            Integer maxUtteranceLength,
            Integer maxConversationTurns
    ) {
    }

    /** Converts this authoring form into the validated value model, computing the canonical Skill hash. */
    public SegmentSkillDefinition toDefinition() {
        return SegmentSkillDefinition.publish(
                SegmentSkillWire.parseSchemaVersion(schemaVersion),
                requireText(skillId, "skillId"),
                requireText(skillVersion, "skillVersion"),
                SegmentSkillWire.parseCapabilityState(capabilityState),
                requireText(evidenceClass, "evidenceClass"),
                requirePins(contractPins),
                SegmentSkillWire.tools(tools),
                SegmentSkillWire.dispositions(inputDispositions, "inputDispositions"),
                SegmentSkillWire.dispositions(outputDispositions, "outputDispositions"),
                SegmentSkillWire.terminalStates(terminalStates),
                requireLimits(limits),
                SegmentSkillWire.dataClassification(dataClassification),
                requireSampleFlag(sampleOutputPermitted),
                requireText(ambiguityStopsAt, "ambiguityStopsAt"),
                requireFailureBehavior(failureBehavior));
    }

    private static SegmentSkillDefinition.ContractPins requirePins(ContractPinsResource pins) {
        if (pins == null) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.MISSING_FIELD, "A Skill resource requires contract pins");
        }
        return new SegmentSkillDefinition.ContractPins(
                pins.agentContractVersion(), pins.intentVersion(), pins.dslVersion(), pins.canonicalizerVersion(),
                pins.resolverVersion(), pins.validatorVersion(), pins.plannerVersion(),
                pins.previewContractVersion(), pins.toolSchemaVersion());
    }

    private static SegmentSkillDefinition.Bounds requireLimits(LimitsResource limits) {
        if (limits == null) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.MISSING_FIELD, "A Skill resource requires bounded limits");
        }
        return new SegmentSkillDefinition.Bounds(
                requireInt(limits.maxSteps(), "limits.maxSteps"),
                requireInt(limits.maxToolCalls(), "limits.maxToolCalls"),
                requireInt(limits.maxToolPayloadBytes(), "limits.maxToolPayloadBytes"),
                requireInt(limits.maxModelOutputBytes(), "limits.maxModelOutputBytes"),
                requireInt(limits.maxUtteranceLength(), "limits.maxUtteranceLength"),
                requireInt(limits.maxConversationTurns(), "limits.maxConversationTurns"));
    }

    private static boolean requireSampleFlag(Boolean sampleOutputPermitted) {
        if (sampleOutputPermitted == null) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.MISSING_FIELD, "A Skill resource requires the sample-output flag");
        }
        return sampleOutputPermitted;
    }

    private static int requireInt(Integer value, String field) {
        if (value == null) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.MISSING_FIELD, "A Skill limit is required: " + field);
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.MISSING_FIELD, "A Skill field is required: " + field);
        }
        return value;
    }

    private static Map<String, String> requireFailureBehavior(Map<String, String> failureBehavior) {
        if (failureBehavior == null) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.MISSING_FIELD, "A Skill resource requires a failure behaviour");
        }
        return failureBehavior;
    }
}