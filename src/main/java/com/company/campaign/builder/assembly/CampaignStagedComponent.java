package com.company.campaign.builder.assembly;

import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.builder.proposal.ProposalOperation;
import com.company.campaign.builder.proposal.ProposalOperationKind;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One bounded, immutable staged component of a Campaign assembly snapshot.
 *
 * <p>It carries the component identity, the owning Skill, the exact accepted {@link ProposalOperation} and the
 * resolved catalog references. It is a view over accepted artifacts only: it never carries raw model output,
 * provider output, a template body, a reward secret, a credential or restricted data.
 */
public record CampaignStagedComponent(
        String componentId,
        String skillId,
        String capabilityCode,
        ProposalOperation operation,
        List<ResolvedCatalogReference> references,
        ContentHash artifactHash
) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public CampaignStagedComponent {
        if (componentId == null || !IDENTIFIER.matcher(componentId).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.ARTIFACT_INVALID,
                    "A staged component requires a bounded component identity.");
        }
        Objects.requireNonNull(skillId, "skillId");
        Objects.requireNonNull(operation, "operation");
        if (!componentId.equals(operation.target().value())) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.ARTIFACT_INVALID,
                    "A staged component identity must be the one its operation addresses.");
        }
        if (!Objects.equals(capabilityCode, operation.capabilityCode())) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.ARTIFACT_INVALID,
                    "A staged component capability must be the one its operation declares.");
        }
        references = references == null ? List.of() : List.copyOf(new ArrayList<>(references));
        Objects.requireNonNull(artifactHash, "artifactHash");
    }

    /** The kind of the staged operation. */
    public ProposalOperationKind operationKind() {
        return operation.kind();
    }

    /** A bounded diagnostic: the identity form and the operation kind, never a payload value. */
    @Override
    public String toString() {
        return "CampaignStagedComponent[componentId=" + componentId
                + ", skillId=" + skillId
                + ", capability=" + (capabilityCode == null ? "-" : capabilityCode)
                + ", operation=" + operation.kind() + ']';
    }
}