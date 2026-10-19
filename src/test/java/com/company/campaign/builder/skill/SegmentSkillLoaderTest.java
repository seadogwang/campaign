package com.company.campaign.builder.skill;

import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.security.data.DataClassification;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requirement 1 and 12: the versioned Segment Skill loads strictly, its canonical hash is stable and covers every
 * semantic field, and every forbidden capability class, malformed encoding, duplicate/unknown/missing/null
 * field, alternate casing, unknown contract version, unsupported terminal state and unsafe data classification
 * is rejected with one stable code.
 */
class SegmentSkillLoaderTest {

    private static final Path RESOURCE =
            Path.of("src", "main", "resources", "skills", "segment-rule-based-v1.json");

    private static String committed() {
        try {
            return Files.readString(RESOURCE, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String mutate(String oldText, String newText) {
        String json = committed();
        assertThat(json).contains(oldText);
        return json.replace(oldText, newText);
    }

    private static String mutateOnce(String oldText, String newText) {
        String json = committed();
        int index = json.indexOf(oldText);
        assertThat(index).isGreaterThanOrEqualTo(0);
        return json.substring(0, index) + newText + json.substring(index + oldText.length());
    }

    private static void assertRejected(byte[] bytes, String code) {
        assertThatThrownBy(() -> new SegmentSkillLoader().parse(bytes))
                .isInstanceOf(SegmentSkillException.class)
                .extracting(failure -> ((SegmentSkillException) failure).code())
                .isEqualTo(code);
    }

    private static void assertRejected(String json, String code) {
        assertRejected(json.getBytes(StandardCharsets.UTF_8), code);
    }

    @Test
    void loadsTheCommittedSkillWithTheExactIdentityAllowlistAndEnums() {
        SegmentSkillDefinition skill = new SegmentSkillLoader().loadDefault();

        assertThat(skill.schemaVersion()).isEqualTo(ContractVersions.CURRENT);
        assertThat(skill.skillId()).isEqualTo("segment.rule-based");
        assertThat(skill.skillVersion()).isEqualTo("1.0.0");
        assertThat(skill.capabilityState()).isEqualTo(SegmentCapabilityState.EXPERIMENTAL);
        assertThat(skill.evidenceClass()).isEqualTo("DEVELOPMENT_ONLY");
        assertThat(skill.allowedToolIds()).containsExactly(
                "metadata.discover", "intent.resolve", "dsl.validate", "plan.build",
                "preview.safe", "explanation.grounded");
        // This Skill version's flow never executes metadata.detail, so the committed resource does not permit
        // it: the committed Skill and the adapter describe the flow they actually execute.
        assertThat(skill.allowedToolIds()).doesNotContain("metadata.detail");
        assertThat(skill.inputDispositions()).containsExactly(
                Disposition.RESOLVED, Disposition.NEEDS_INPUT, Disposition.UNSUPPORTED, Disposition.DENIED,
                Disposition.INVALID);
        assertThat(skill.terminalStates()).containsExactly(
                SegmentSkillTerminalStates.NEEDS_INPUT, SegmentSkillTerminalStates.UNSUPPORTED,
                SegmentSkillTerminalStates.DENIED, SegmentSkillTerminalStates.INVALID,
                SegmentSkillTerminalStates.READY_FOR_PREVIEW);
        assertThat(skill.ambiguityStopsAt()).isEqualTo(SegmentSkillTerminalStates.NEEDS_INPUT);
        assertThat(skill.sampleOutputPermitted()).isFalse();
        assertThat(skill.dataClassification()).isEqualTo(DataClassification.INTERNAL);
        assertThat(skill.limits().maxToolCalls()).isEqualTo(6);
        assertThat(skill.limits().maxSteps()).isGreaterThanOrEqualTo(8);
        assertThat(skill.failureCode("metadataDiscoveryFailure"))
                .isEqualTo("SEGMENT_AGENT_METADATA_DISCOVERY_REJECTED");
        // Moved with T18: the committed Skill pins the live planner version, so bumping
        // PlanVersions.PLANNER_VERSION to segment-query-planner/1.1 moved this canonical hash too. The
        // committed resource differs from its previous bytes by exactly that one pin value, which the loader
        // itself re-checks against SegmentSkillContractVersions before it will parse the Skill at all.
        assertThat(skill.canonicalHash().value()).isEqualTo("sha256:10c16a32c334b1d0fc2602e8662c22e6baaf624a76826996c201d4c24791d636");
        assertThat(skill.contractPins().intentVersion()).isEqualTo("1.3");
    }

    @Test
    void canonicalHashIsStableAcrossLoadsAndCoversEverySemanticField() {
        SegmentSkillDefinition first = new SegmentSkillLoader().loadDefault();
        SegmentSkillDefinition second = new SegmentSkillLoader().parse(committed().getBytes(StandardCharsets.UTF_8));
        assertThat(first.canonicalHash()).isEqualTo(second.canonicalHash());

        SegmentSkillDefinition renamed = withSkillVersion("1.0.1");
        assertThat(renamed.canonicalHash()).isNotEqualTo(first.canonicalHash());

        SegmentSkillDefinition relocated = SegmentSkillDefinition.publish(
                first.schemaVersion(), first.skillId(), first.skillVersion(), first.capabilityState(),
                first.evidenceClass(), first.contractPins(),
                first.tools().stream().map(tool -> new SegmentSkillDefinition.ToolPin(
                        tool.toolId(), tool.toolVersion(), tool.rank(), tool.maxPayloadBytes() + 1)).toList(),
                first.inputDispositions(), first.outputDispositions(), first.terminalStates(), first.limits(),
                first.dataClassification(), first.sampleOutputPermitted(), first.ambiguityStopsAt(),
                first.failureBehavior());
        assertThat(relocated.canonicalHash()).isNotEqualTo(first.canonicalHash());
    }

    private static SegmentSkillDefinition withSkillVersion(String version) {
        SegmentSkillDefinition first = new SegmentSkillLoader().loadDefault();
        return SegmentSkillDefinition.publish(
                first.schemaVersion(), first.skillId(), version, first.capabilityState(), first.evidenceClass(),
                first.contractPins(), first.tools(), first.inputDispositions(), first.outputDispositions(),
                first.terminalStates(), first.limits(), first.dataClassification(), first.sampleOutputPermitted(),
                first.ambiguityStopsAt(), first.failureBehavior());
    }

    @Test
    void rejectsEveryForbiddenCapabilityInTheToolAllowlist() {
        for (String forbidden : List.of(
                "save.draft", "apply.plan", "publish.campaign", "pause.campaign", "resume.campaign",
                "retry.run", "archive.draft", "runtime.activate", "sql.query", "jdbc.execute",
                "repository.read", "provider.credential", "secret.read")) {
            String json = mutateOnce("\"metadata.discover\"", "\"" + forbidden + "\"");
            assertRejected(json, SegmentSkillErrorCodes.TOOL_FORBIDDEN_CAPABILITY);
        }
    }

    @Test
    void rejectsUnknownDuplicateAndWrongVersionTools() {
        assertRejected(mutateOnce("\"metadata.discover\"", "\"metadata.summarize\""),
                SegmentSkillErrorCodes.TOOL_UNKNOWN);
        assertRejected(mutateOnce("\"toolId\": \"intent.resolve\",\n      \"toolVersion\": \"1.0\"",
                        "\"toolId\": \"metadata.discover\",\n      \"toolVersion\": \"1.0\""),
                SegmentSkillErrorCodes.TOOL_DUPLICATE);
        assertRejected(mutateOnce("\"toolId\": \"intent.resolve\",\n      \"toolVersion\": \"1.0\"",
                "\"toolId\": \"intent.resolve\",\n      \"toolVersion\": \"2.0\""),
                SegmentSkillErrorCodes.TOOL_VERSION_UNKNOWN);
    }

    @Test
    void rejectsUnknownContractVersionCapabilityStateEvidenceAndAmbiguityTerminal() {
        assertRejected(mutateOnce("\"canonicalizerVersion\": \"segment-canonicalizer/1.2\"",
                "\"canonicalizerVersion\": \"segment-canonicalizer/9.9\""),
                SegmentSkillErrorCodes.CONTRACT_VERSION_UNKNOWN);
        assertRejected(mutateOnce("\"capabilityState\": \"EXPERIMENTAL\"", "\"capabilityState\": \"ACTIVE\""),
                SegmentSkillErrorCodes.CAPABILITY_STATE_UNSUPPORTED);
        assertRejected(mutateOnce("\"evidenceClass\": \"DEVELOPMENT_ONLY\"", "\"evidenceClass\": \"GATE_ELIGIBLE\""),
                SegmentSkillErrorCodes.EVIDENCE_CLASS_INVALID);
        assertRejected(mutateOnce("\"ambiguityStopsAt\": \"NEEDS_INPUT\"", "\"ambiguityStopsAt\": \"INVALID\""),
                SegmentSkillErrorCodes.AMBIGUITY_TERMINAL_INVALID);
    }

    @Test
    void rejectsUnsupportedTerminalStatesIncludingWaitingUserSave() {
        assertRejected(mutateOnce("\"READY_FOR_PREVIEW\"", "\"WAITING_USER_SAVE\""),
                SegmentSkillErrorCodes.TERMINAL_STATE_UNSUPPORTED);
        assertRejected(mutateOnce("\"READY_FOR_PREVIEW\"", "\"SAVED\""),
                SegmentSkillErrorCodes.TERMINAL_STATE_UNSUPPORTED);
    }

    @Test
    void rejectsUnsafeDataClassificationAndSampleOutputPermission() {
        assertRejected(mutateOnce("\"dataClassification\": \"INTERNAL\"", "\"dataClassification\": \"RESTRICTED\""),
                SegmentSkillErrorCodes.DATA_CLASSIFICATION_UNSAFE);
        assertRejected(mutateOnce("\"sampleOutputPermitted\": false", "\"sampleOutputPermitted\": true"),
                SegmentSkillErrorCodes.SAMPLE_OUTPUT_FORBIDDEN);
    }

    @Test
    void rejectsAlternateCasingOfEveryClosedVocabulary() {
        assertRejected(mutateOnce("\"EXPERIMENTAL\"", "\"experimental\""), SegmentSkillErrorCodes.CASING_INVALID);
        assertRejected(mutateOnce("\"NEEDS_INPUT\"", "\"needs_input\""), SegmentSkillErrorCodes.CASING_INVALID);
        assertRejected(mutateOnce("\"INTERNAL\"", "\"internal\""), SegmentSkillErrorCodes.CASING_INVALID);
        assertRejected(mutateOnce("\"RESOLVED\"", "\"Resolved\""), SegmentSkillErrorCodes.CASING_INVALID);
    }

    @Test
    void rejectsUnknownMissingNullAndDuplicateFields() {
        assertRejected(mutateOnce("\"skillVersion\": \"1.0.0\",", "\"skillVersion\": \"1.0.0\", \"extra\": 1,"),
                SegmentSkillErrorCodes.UNKNOWN_FIELD);
        assertRejected(mutateOnce("\"skillVersion\": \"1.0.0\",", ""), SegmentSkillErrorCodes.MISSING_FIELD);
        assertRejected(mutateOnce("\"skillId\": \"segment.rule-based\"", "\"skillId\": null"),
                SegmentSkillErrorCodes.MISSING_FIELD);
        assertRejected(mutateOnce("\"skillVersion\": \"1.0.0\"", "\"skillVersion\": \"1.0.0\", \"skillVersion\": \"1.0.0\""),
                SegmentSkillErrorCodes.JSON_DUPLICATE_KEY);
    }

    @Test
    void rejectsMalformedEncodingMalformedJsonAndOversizedInput() {
        assertRejected(new byte[] {(byte) 0x7B, (byte) 0xC3, (byte) 0x28, (byte) 0x7D},
                SegmentSkillErrorCodes.ENCODING_INVALID);
        assertRejected("{ not json", SegmentSkillErrorCodes.JSON_MALFORMED);
        assertRejected(new byte[SegmentSkillLimits.MAX_RESOURCE_BYTES + 1], SegmentSkillErrorCodes.RESOURCE_OVERSIZED);
        assertRejected(new byte[0], SegmentSkillErrorCodes.RESOURCE_MISSING);
    }

    @Test
    void rejectsATamperedHashAndAMissingResource() {
        SegmentSkillDefinition skill = new SegmentSkillLoader().loadDefault();
        assertThatThrownBy(() -> new SegmentSkillDefinition(
                skill.schemaVersion(), skill.skillId(), skill.skillVersion(), skill.capabilityState(),
                skill.evidenceClass(), skill.contractPins(), skill.tools(), skill.inputDispositions(),
                skill.outputDispositions(), skill.terminalStates(), skill.limits(), skill.dataClassification(),
                skill.sampleOutputPermitted(), skill.ambiguityStopsAt(), skill.failureBehavior(),
                new com.company.campaign.shared.canonical.ContentHash("sha256:" + "0".repeat(64))))
                .isInstanceOf(SegmentSkillException.class)
                .extracting(failure -> ((SegmentSkillException) failure).code())
                .isEqualTo(SegmentSkillErrorCodes.HASH_INVALID);

        assertThatThrownBy(() -> new SegmentSkillLoader().load("skills/does-not-exist.json"))
                .isInstanceOf(SegmentSkillException.class)
                .extracting(failure -> ((SegmentSkillException) failure).code())
                .isEqualTo(SegmentSkillErrorCodes.RESOURCE_MISSING);
    }

    @Test
    void aDeclaredBoundAboveItsAbsoluteMaximumIsRejected() {
        assertRejected(mutateOnce("\"maxToolCalls\": 6", "\"maxToolCalls\": 64"),
                SegmentSkillErrorCodes.LIMIT_INVALID);
    }

    @Test
    void theFailureBehaviourIsAClosedNonEmptyMachineCodeMap() {
        SegmentSkillDefinition skill = new SegmentSkillLoader().loadDefault();
        Map<String, String> behaviour = skill.failureBehavior();
        assertThat(behaviour).isNotEmpty();
        List<String> codes = new ArrayList<>(behaviour.values());
        assertThat(codes).allSatisfy(code -> assertThat(code).matches("^[A-Z][A-Z0-9_]{0,63}$"));
    }
}