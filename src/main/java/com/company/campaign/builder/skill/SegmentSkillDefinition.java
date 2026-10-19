package com.company.campaign.builder.skill;

import com.company.campaign.builder.tool.SegmentToolIds;
import com.company.campaign.builder.tool.SegmentToolVersions;
import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.security.data.DataClassification;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One immutable, strict, versioned Skill definition.
 *
 * <p>It pins and hashes every execution-relevant fact: the exact Skill id and version, the compatible Intent,
 * DSL, canonicalizer, resolver, validator, planner and Preview contract versions, the exact ordered set of
 * permitted model-callable Tool ids with their server-owned versions, the input/output dispositions, the closed
 * terminal Agent states, bounded step/tool-call limits, the data classification and sample-output permission,
 * the failure behaviour and the fact that ambiguity stops at {@code NEEDS_INPUT}. The canonical
 * {@link #skillHash()} covers every one of those semantic fields and is deterministic.
 *
 * <p>Construction validates the whole model and re-proves the declared hash, so neither a forged hash nor a
 * contradictory definition can be built directly or read from a resource. The Skill is usage guidance only: it
 * carries no authorization, no Metadata semantics, no business alias, no validation or query rule, and no
 * Java-side Loyalty field name, request-specific mapping or storage identifier.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SegmentSkillDefinition(
        SchemaVersion schemaVersion,
        String skillId,
        String skillVersion,
        SegmentCapabilityState capabilityState,
        String evidenceClass,
        ContractPins contractPins,
        List<ToolPin> tools,
        List<Disposition> inputDispositions,
        List<Disposition> outputDispositions,
        List<String> terminalStates,
        Bounds limits,
        DataClassification dataClassification,
        boolean sampleOutputPermitted,
        String ambiguityStopsAt,
        Map<String, String> failureBehavior,
        ContentHash skillHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern REFERENCE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

    public SegmentSkillDefinition {
        schemaVersion = requirePresent(schemaVersion, "schemaVersion");
        if (!ContractVersions.isCurrent(schemaVersion)) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.VERSION_UNSUPPORTED, "A Skill requires the current contract schema version");
        }
        skillId = requireReferenceId(skillId, "skillId");
        skillVersion = requireReferenceId(skillVersion, "skillVersion");
        if (capabilityState != SegmentCapabilityState.EXPERIMENTAL) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.CAPABILITY_STATE_UNSUPPORTED,
                    "A Skill in this task can only be EXPERIMENTAL");
        }
        evidenceClass = requirePresent(evidenceClass, "evidenceClass");
        if (!evidenceClass.equals("DEVELOPMENT_ONLY")) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.EVIDENCE_CLASS_INVALID, "A Skill in this task can only be DEVELOPMENT_ONLY");
        }
        contractPins = requirePresent(contractPins, "contractPins");
        tools = validateTools(tools);
        inputDispositions = validateDispositions(inputDispositions, "inputDispositions");
        outputDispositions = validateDispositions(outputDispositions, "outputDispositions");
        terminalStates = validateTerminalStates(terminalStates);
        limits = requirePresent(limits, "limits");
        if (dataClassification == null) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.NULL_FIELD, "A Skill requires a data classification");
        }
        if (dataClassification.isRestricted()) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.DATA_CLASSIFICATION_UNSAFE,
                    "A Skill definition may not be classified RESTRICTED");
        }
        if (sampleOutputPermitted) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.SAMPLE_OUTPUT_FORBIDDEN,
                    "A Skill in this task may not permit sample output");
        }
        ambiguityStopsAt = requirePresent(ambiguityStopsAt, "ambiguityStopsAt");
        if (!SegmentSkillTerminalStates.NEEDS_INPUT.equals(ambiguityStopsAt)) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.AMBIGUITY_TERMINAL_INVALID, "Ambiguity must stop at NEEDS_INPUT");
        }
        if (!terminalStates.contains(ambiguityStopsAt)) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.TERMINAL_STATE_UNSUPPORTED,
                    "A Skill must declare its ambiguity terminal state");
        }
        if (outputDispositions.stream().noneMatch(disposition -> Disposition.NEEDS_INPUT == disposition)) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.DISPOSITION_UNSUPPORTED,
                    "A Skill output disposition set must include NEEDS_INPUT");
        }
        failureBehavior = validateFailureBehavior(failureBehavior);
        ContentHash expected = CANONICAL_JSON.hash(new Material(
                schemaVersion, skillId, skillVersion, capabilityState, evidenceClass, contractPins, tools,
                inputDispositions, outputDispositions, terminalStates, limits, dataClassification,
                sampleOutputPermitted, ambiguityStopsAt, failureBehavior));
        if (skillHash == null || !skillHash.equals(expected)) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.HASH_INVALID,
                    "A Skill hash must be the canonical hash of its semantic fields");
        }
    }

    /** Publishes a definition: computes the canonical hash from explicit semantic material, then verifies it. */
    public static SegmentSkillDefinition publish(
            SchemaVersion schemaVersion,
            String skillId,
            String skillVersion,
            SegmentCapabilityState capabilityState,
            String evidenceClass,
            ContractPins contractPins,
            List<ToolPin> tools,
            List<Disposition> inputDispositions,
            List<Disposition> outputDispositions,
            List<String> terminalStates,
            Bounds limits,
            DataClassification dataClassification,
            boolean sampleOutputPermitted,
            String ambiguityStopsAt,
            Map<String, String> failureBehavior) {
        ContentHash hash = CANONICAL_JSON.hash(new Material(
                schemaVersion, skillId, skillVersion, capabilityState, evidenceClass, contractPins, tools,
                inputDispositions, outputDispositions, terminalStates, limits, dataClassification,
                sampleOutputPermitted, ambiguityStopsAt, failureBehavior));
        return new SegmentSkillDefinition(
                schemaVersion, skillId, skillVersion, capabilityState, evidenceClass, contractPins, tools,
                inputDispositions, outputDispositions, terminalStates, limits, dataClassification,
                sampleOutputPermitted, ambiguityStopsAt, failureBehavior, hash);
    }

    /** The canonical hash covering every semantic field of this Skill definition. */
    public ContentHash canonicalHash() {
        return skillHash;
    }

    /** The ordered Tool ids, in the exact order the Skill admits them. */
    public List<String> allowedToolIds() {
        List<String> ids = new ArrayList<>(tools.size());
        for (ToolPin tool : tools) {
            ids.add(tool.toolId());
        }
        return List.copyOf(ids);
    }

    /** The Tool pin for one identifier, or null when the Skill does not allow it. */
    public ToolPin tool(String toolId) {
        for (ToolPin tool : tools) {
            if (tool.toolId().equals(toolId)) {
                return tool;
            }
        }
        return null;
    }

    /** The stable failure code declared for one failure category. */
    public String failureCode(String category) {
        return failureBehavior.get(category);
    }

    // ------------------------------------------------------------------ validation

    private static List<ToolPin> validateTools(List<ToolPin> tools) {
        if (tools == null || tools.isEmpty()) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.MISSING_FIELD, "A Skill requires an ordered Tool allowlist");
        }
        if (tools.size() > SegmentSkillLimits.MAX_TOOLS) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.LIMIT_INVALID, "A Skill Tool allowlist exceeds its bound");
        }
        List<ToolPin> copy = new ArrayList<>(tools.size());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int index = 0; index < tools.size(); index++) {
            ToolPin tool = requirePresent(tools.get(index), "tools");
            if (tool.rank() != index) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.TOOL_UNKNOWN, "A Tool pin must carry its declared rank");
            }
            if (SegmentToolIds.attemptsForbiddenCapability(tool.toolId())) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.TOOL_FORBIDDEN_CAPABILITY,
                        "A Skill allowlist may not name a host-only or unsafe capability");
            }
            if (!SegmentToolIds.isKnown(tool.toolId())) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.TOOL_UNKNOWN, "A Skill allowlist may only name server-owned Tools");
            }
            if (!seen.add(tool.toolId())) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.TOOL_DUPLICATE, "A Skill allowlist may not repeat a Tool identifier");
            }
            if (!SegmentToolVersions.matches(tool.toolId(), tool.toolVersion())) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.TOOL_VERSION_UNKNOWN,
                        "A Skill Tool pin must name the server-owned Tool version");
            }
            copy.add(tool);
        }
        return List.copyOf(copy);
    }

    private static List<Disposition> validateDispositions(List<Disposition> dispositions, String field) {
        if (dispositions == null || dispositions.isEmpty()) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.MISSING_FIELD, "A Skill requires declared dispositions: " + field);
        }
        List<Disposition> copy = new ArrayList<>(dispositions.size());
        for (Disposition disposition : dispositions) {
            if (disposition == null) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.NULL_FIELD, "A Skill disposition must not be null: " + field);
            }
            if (copy.contains(disposition)) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.DISPOSITION_UNSUPPORTED,
                        "A Skill disposition set may not repeat a disposition: " + field);
            }
            copy.add(disposition);
        }
        return List.copyOf(copy);
    }

    private static List<String> validateTerminalStates(List<String> states) {
        if (states == null || states.isEmpty()) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.MISSING_FIELD, "A Skill requires declared terminal states");
        }
        List<String> copy = new ArrayList<>(states.size());
        for (String state : states) {
            if (state == null || !SegmentSkillTerminalStates.isTerminal(state)) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.TERMINAL_STATE_UNSUPPORTED,
                        "A Skill may only declare a closed terminal Agent state");
            }
            if (copy.contains(state)) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.TERMINAL_STATE_UNSUPPORTED,
                        "A Skill terminal state list may not repeat a state");
            }
            copy.add(state);
        }
        if (!copy.contains(SegmentSkillTerminalStates.READY_FOR_PREVIEW)) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.TERMINAL_STATE_UNSUPPORTED,
                    "A Skill must declare READY_FOR_PREVIEW");
        }
        return List.copyOf(copy);
    }

    private static Map<String, String> validateFailureBehavior(Map<String, String> failureBehavior) {
        if (failureBehavior == null || failureBehavior.isEmpty()) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.MISSING_FIELD, "A Skill requires a declared failure behaviour");
        }
        if (failureBehavior.size() > SegmentSkillLimits.MAX_FAILURE_CODES) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.LIMIT_INVALID, "A Skill failure behaviour exceeds its bound");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : failureBehavior.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.NULL_FIELD, "A Skill failure behaviour entry must be present");
            }
            if (!key.matches("^[a-z][A-Za-z0-9]{0,63}$") || !value.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.CASING_INVALID,
                        "A Skill failure behaviour entry is not a stable machine code");
            }
            copy.put(key, value);
        }
        return Map.copyOf(copy);
    }

    private static <T> T requirePresent(T value, String field) {
        if (value == null) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.NULL_FIELD, "A Skill field is required: " + field);
        }
        return value;
    }

    private static String requireReferenceId(String value, String field) {
        requirePresent(value, field);
        if (!REFERENCE_ID.matcher(value).matches()) {
            throw SegmentSkillException.invalid(
                    SegmentSkillErrorCodes.ID_INVALID, "A Skill identifier is malformed: " + field);
        }
        return value;
    }

    /** One ordered Tool pin: the server-owned identifier, its version, its rank and its payload bound. */
    public record ToolPin(String toolId, String toolVersion, int rank, int maxPayloadBytes) {

        public ToolPin {
            requirePresent(toolId, "tool.toolId");
            requirePresent(toolVersion, "tool.toolVersion");
            if (rank < 0) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.LIMIT_INVALID, "A Tool rank must not be negative");
            }
            SegmentSkillLimits.requireBounded(maxPayloadBytes, SegmentSkillLimits.MAX_TOOL_PAYLOAD_BYTES, "maxPayloadBytes");
        }
    }

    /** The exact contract versions a Skill was authored against. */
    public record ContractPins(
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

        public ContractPins {
            requirePin(agentContractVersion, SegmentSkillContractVersions.AGENT_CONTRACT_VERSION, "agentContractVersion");
            requirePin(intentVersion, SegmentSkillContractVersions.INTENT_VERSION, "intentVersion");
            requirePin(dslVersion, SegmentSkillContractVersions.DSL_VERSION, "dslVersion");
            requirePin(canonicalizerVersion, SegmentSkillContractVersions.CANONICALIZER_VERSION, "canonicalizerVersion");
            requirePin(resolverVersion, SegmentSkillContractVersions.RESOLVER_VERSION, "resolverVersion");
            requirePin(validatorVersion, SegmentSkillContractVersions.VALIDATOR_VERSION, "validatorVersion");
            requirePin(plannerVersion, SegmentSkillContractVersions.PLANNER_VERSION, "plannerVersion");
            requirePin(previewContractVersion, SegmentSkillContractVersions.PREVIEW_CONTRACT_VERSION, "previewContractVersion");
            requirePin(toolSchemaVersion, SegmentSkillContractVersions.TOOL_SCHEMA_VERSION, "toolSchemaVersion");
        }

        private static void requirePin(String actual, String accepted, String field) {
            if (actual == null) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.NULL_FIELD, "A Skill contract pin is required: " + field);
            }
            if (!actual.equals(accepted)) {
                throw SegmentSkillException.invalid(
                        SegmentSkillErrorCodes.CONTRACT_VERSION_UNKNOWN,
                        "A Skill pins an unknown contract version: " + field);
            }
        }
    }

    /** The bounded step, Tool-call and payload limits one Skill run must respect. */
    public record Bounds(
            int maxSteps,
            int maxToolCalls,
            int maxToolPayloadBytes,
            int maxModelOutputBytes,
            int maxUtteranceLength,
            int maxConversationTurns
    ) {

        public Bounds {
            SegmentSkillLimits.requireBounded(maxSteps, SegmentSkillLimits.MAX_STEPS, "maxSteps");
            SegmentSkillLimits.requireBounded(maxToolCalls, SegmentSkillLimits.MAX_TOOL_CALLS, "maxToolCalls");
            SegmentSkillLimits.requireBounded(
                    maxToolPayloadBytes, SegmentSkillLimits.MAX_TOOL_PAYLOAD_BYTES, "maxToolPayloadBytes");
            SegmentSkillLimits.requireBounded(
                    maxModelOutputBytes, SegmentSkillLimits.MAX_MODEL_OUTPUT_BYTES, "maxModelOutputBytes");
            SegmentSkillLimits.requireBounded(
                    maxUtteranceLength, SegmentSkillLimits.MAX_UTTERANCE_LENGTH, "maxUtteranceLength");
            SegmentSkillLimits.requireBounded(
                    maxConversationTurns, SegmentSkillLimits.MAX_CONVERSATION_TURNS, "maxConversationTurns");
        }
    }

    /** The exact semantic material the canonical Skill hash covers, excluding the hash field itself. */
    private record Material(
            SchemaVersion schemaVersion,
            String skillId,
            String skillVersion,
            SegmentCapabilityState capabilityState,
            String evidenceClass,
            ContractPins contractPins,
            List<ToolPin> tools,
            List<Disposition> inputDispositions,
            List<Disposition> outputDispositions,
            List<String> terminalStates,
            Bounds limits,
            DataClassification dataClassification,
            boolean sampleOutputPermitted,
            String ambiguityStopsAt,
            Map<String, String> failureBehavior) {

        private Material {
            // Null-tolerant: the canonical hash is computed before validation so a missing field still fails
            // with the stable Skill code rather than a raw NullPointerException from this material record.
            tools = tools == null ? null : List.copyOf(tools);
            inputDispositions = inputDispositions == null ? null : List.copyOf(inputDispositions);
            outputDispositions = outputDispositions == null ? null : List.copyOf(outputDispositions);
            terminalStates = terminalStates == null ? null : List.copyOf(terminalStates);
            failureBehavior = failureBehavior == null ? null : Map.copyOf(failureBehavior);
        }
    }
}