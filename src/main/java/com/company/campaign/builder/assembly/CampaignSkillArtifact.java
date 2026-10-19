package com.company.campaign.builder.assembly;

import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.builder.proposal.ProposalOperation;
import com.company.campaign.builder.proposal.ProposalOperationKind;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One accepted Skill artifact the host may stage into the ephemeral Campaign assembly workspace.
 *
 * <p>It binds the exact run, Skill ID/version, Tool ID/version, catalog hash and one typed Proposal operation,
 * so an artifact can only be accepted by the workspace that owns the same run and catalog. The operation is the
 * accepted {@link ProposalOperation}, never a second patch language: an upsert carries an exact capability code
 * and bounded payload, a delete names an existing logical identity and nothing else, and a move names one
 * relative anchor.
 *
 * <p>{@link #artifactHash()} covers every binding fact, the operation and the resolved references, so a
 * changed Skill, Tool, catalog, payload or base revision changes the covered hash. The value carries no trusted
 * identity, revision override, lifecycle state or Apply/Publish flag.
 */
public record CampaignSkillArtifact(
        String conversationId,
        String runId,
        String skillId,
        String skillVersion,
        String toolId,
        String toolVersion,
        ContentHash toolCatalogHash,
        ProposalOperation operation,
        List<ResolvedCatalogReference> references,
        ContentHash artifactHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public CampaignSkillArtifact {
        requireIdentifier(conversationId, "conversationId");
        requireIdentifier(runId, "runId");
        requireIdentifier(skillId, "skillId");
        requireIdentifier(skillVersion, "skillVersion");
        requireIdentifier(toolId, "toolId");
        requireIdentifier(toolVersion, "toolVersion");
        Objects.requireNonNull(toolCatalogHash, "toolCatalogHash");
        Objects.requireNonNull(operation, "operation");
        references = references == null ? List.of() : List.copyOf(new ArrayList<>(references));
        ContentHash expected = CANONICAL_JSON.hash(material(
                conversationId, runId, skillId, skillVersion, toolId, toolVersion, toolCatalogHash, operation,
                references));
        if (artifactHash == null || !artifactHash.equals(expected)) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.ARTIFACT_INVALID,
                    "An artifact hash must cover its own binding facts, operation and references.");
        }
    }

    /** Publishes an artifact, computing its canonical hash from the exact binding facts. */
    public static CampaignSkillArtifact of(
            String conversationId,
            String runId,
            String skillId,
            String skillVersion,
            String toolId,
            String toolVersion,
            ContentHash toolCatalogHash,
            ProposalOperation operation,
            List<ResolvedCatalogReference> references) {
        List<ResolvedCatalogReference> copy = references == null ? List.of() : List.copyOf(references);
        ContentHash hash = CANONICAL_JSON.hash(material(
                conversationId, runId, skillId, skillVersion, toolId, toolVersion, toolCatalogHash, operation,
                copy));
        return new CampaignSkillArtifact(conversationId, runId, skillId, skillVersion, toolId, toolVersion,
                toolCatalogHash, operation, copy, hash);
    }

    /** The single component identity this artifact addresses, in whichever identity form the operation used. */
    public String componentId() {
        return operation.target().value();
    }

    /** The capability code of an upsert, or null for a delete or a move. */
    public String capabilityCode() {
        return operation.capabilityCode();
    }

    /** The kind of the staged operation. */
    public ProposalOperationKind operationKind() {
        return operation.kind();
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.ARTIFACT_INVALID,
                    "An artifact requires a bounded identifier: " + field);
        }
    }

    private static Map<String, Object> material(
            String conversationId,
            String runId,
            String skillId,
            String skillVersion,
            String toolId,
            String toolVersion,
            ContentHash toolCatalogHash,
            ProposalOperation operation,
            List<ResolvedCatalogReference> references) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("conversationId", conversationId);
        material.put("runId", runId);
        material.put("skillId", skillId);
        material.put("skillVersion", skillVersion);
        material.put("toolId", toolId);
        material.put("toolVersion", toolVersion);
        material.put("toolCatalogHash", toolCatalogHash);
        material.put("operation", operation);
        material.put("references", references);
        return material;
    }

    /** A bounded diagnostic: the binding identity and the operation kind, never a payload value. */
    @Override
    public String toString() {
        return "CampaignSkillArtifact[skillId=" + skillId
                + ", toolId=" + toolId
                + ", operation=" + operation.kind()
                + ", artifactHash=" + artifactHash + ']';
    }
}