package com.company.campaign.builder.assembly;

import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.builder.proposal.JourneyTopologyMutation;
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
 * One accepted Journey bundle the host may stage into the ephemeral Campaign assembly workspace.
 *
 * <p>A Journey is a bounded set of actual Flow components plus one separate topology, so its artifact is the
 * smallest coherent extension of the accepted single-component shape: it binds the exact run, Skill
 * ID/version, final Tool ID/version, Tool catalog hash, <em>every</em> normalized Flow operation, the one
 * strict topology mutation, the resolved catalog references and one artifact hash.
 *
 * <p>Every operation is an accepted {@link ProposalOperation} upsert of one of the six accepted Flow node
 * capabilities, so a Journey never emits a synthetic capability of its own name. {@link #artifactHash()} covers
 * every binding fact, every operation, the topology mutation and the referenced set: a changed Skill, Tool,
 * catalog, node payload, edge or reference changes the covered hash.
 *
 * <p>Like its single-component sibling it carries no trusted identity, revision override, lifecycle state or
 * Apply/Publish flag.
 */
public record CampaignJourneyArtifact(
        String conversationId,
        String runId,
        String skillId,
        String skillVersion,
        String toolId,
        String toolVersion,
        ContentHash toolCatalogHash,
        List<ProposalOperation> operations,
        JourneyTopologyMutation topologyMutation,
        List<ResolvedCatalogReference> references,
        ContentHash artifactHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public CampaignJourneyArtifact {
        requireIdentifier(conversationId, "conversationId");
        requireIdentifier(runId, "runId");
        requireIdentifier(skillId, "skillId");
        requireIdentifier(skillVersion, "skillVersion");
        requireIdentifier(toolId, "toolId");
        requireIdentifier(toolVersion, "toolVersion");
        Objects.requireNonNull(toolCatalogHash, "toolCatalogHash");
        Objects.requireNonNull(topologyMutation, "topologyMutation");
        if (operations == null || operations.isEmpty()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.ARTIFACT_INVALID,
                    "A Journey artifact requires at least one normalized Flow operation.");
        }
        List<ProposalOperation> operationCopy = new ArrayList<>(operations.size());
        for (ProposalOperation operation : operations) {
            if (operation == null || operation.kind() != ProposalOperationKind.UPSERT) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.ARTIFACT_INVALID,
                        "A Journey artifact carries only node upserts, never a delete or a move.");
            }
            operationCopy.add(operation);
        }
        operations = List.copyOf(operationCopy);
        references = references == null ? List.of() : List.copyOf(new ArrayList<>(references));
        ContentHash expected = CANONICAL_JSON.hash(material(
                conversationId, runId, skillId, skillVersion, toolId, toolVersion, toolCatalogHash,
                operations, topologyMutation, references));
        if (artifactHash == null || !artifactHash.equals(expected)) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.ARTIFACT_INVALID,
                    "A Journey artifact hash must cover its own binding facts, operations and topology.");
        }
    }

    /** Publishes a Journey artifact, computing its canonical hash from the exact binding facts. */
    public static CampaignJourneyArtifact of(
            String conversationId,
            String runId,
            String skillId,
            String skillVersion,
            String toolId,
            String toolVersion,
            ContentHash toolCatalogHash,
            List<ProposalOperation> operations,
            JourneyTopologyMutation topologyMutation,
            List<ResolvedCatalogReference> references) {
        List<ProposalOperation> operationCopy = operations == null ? List.of() : List.copyOf(operations);
        List<ResolvedCatalogReference> referenceCopy = references == null ? List.of() : List.copyOf(references);
        ContentHash hash = CANONICAL_JSON.hash(material(
                conversationId, runId, skillId, skillVersion, toolId, toolVersion, toolCatalogHash,
                operationCopy, topologyMutation, referenceCopy));
        return new CampaignJourneyArtifact(
                conversationId, runId, skillId, skillVersion, toolId, toolVersion, toolCatalogHash,
                operationCopy, topologyMutation, referenceCopy, hash);
    }

    /** Every component identity this bundle addresses, in operation order. */
    public List<String> componentIds() {
        List<String> ids = new ArrayList<>(operations.size());
        for (ProposalOperation operation : operations) {
            ids.add(operation.target().value());
        }
        return List.copyOf(ids);
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.ARTIFACT_INVALID,
                    "A Journey artifact requires a bounded identifier: " + field);
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
            List<ProposalOperation> operations,
            JourneyTopologyMutation topologyMutation,
            List<ResolvedCatalogReference> references) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("conversationId", conversationId);
        material.put("runId", runId);
        material.put("skillId", skillId);
        material.put("skillVersion", skillVersion);
        material.put("toolId", toolId);
        material.put("toolVersion", toolVersion);
        material.put("toolCatalogHash", toolCatalogHash);
        material.put("operations", operations);
        material.put("topologyMutation", topologyMutation);
        material.put("references", references);
        return material;
    }

    /** A bounded diagnostic: the binding identity and the bundle size, never a payload value. */
    @Override
    public String toString() {
        return "CampaignJourneyArtifact[skillId=" + skillId
                + ", toolId=" + toolId
                + ", operations=" + operations.size()
                + ", topology=" + topologyMutation.action()
                + ", artifactHash=" + artifactHash + ']';
    }
}