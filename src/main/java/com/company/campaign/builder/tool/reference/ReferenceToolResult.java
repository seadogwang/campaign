package com.company.campaign.builder.tool.reference;

/**
 * The bounded result of one admitted read-only reference Tool call.
 *
 * <p>It binds the answer to the registered contract that produced it — the server-owned identifier, its exact
 * version and its one answer shape — and carries exactly one of the two bounded outputs, never both. The
 * invariant is enforced here, so a result that claims the discovery contract while carrying a resolution
 * outcome, or that carries an answer of a shape no registered contract produces, cannot be built.
 *
 * <p>There is no field for a written, retained, released, deleted or published Audience Version, and no field
 * carrying a trusted owner, program, DataScope, permission, dependency or Gate fact.
 */
public record ReferenceToolResult(
        String toolId,
        String toolVersion,
        ReferenceToolOutputKind outputKind,
        ReferenceDiscoveryToolOutput discovery,
        ReferenceResolutionToolOutput resolution
) {

    public ReferenceToolResult {
        if (toolId == null || toolVersion == null || outputKind == null) {
            throw ReferenceToolException.invalid(
                    ReferenceToolErrorCodes.REFERENCE_TOOL_INCONSISTENT,
                    "A reference Tool result requires the contract that produced it");
        }
        if (outputKind == ReferenceToolOutputKind.DISCOVERY) {
            if (discovery == null || resolution != null) {
                throw ReferenceToolException.invalid(
                        ReferenceToolErrorCodes.REFERENCE_TOOL_INCONSISTENT,
                        "A discovery result carries exactly one discovery answer");
            }
        } else if (resolution == null || discovery != null) {
            throw ReferenceToolException.invalid(
                    ReferenceToolErrorCodes.REFERENCE_TOOL_INCONSISTENT,
                    "A resolution result carries exactly one resolution answer");
        }
    }

    /** Builds the one result shape of the discovery contract. */
    static ReferenceToolResult ofDiscovery(ReferenceToolContract contract, ReferenceDiscoveryToolOutput output) {
        return new ReferenceToolResult(
                contract.toolId(), contract.toolVersion(), ReferenceToolOutputKind.DISCOVERY, output, null);
    }

    /** Builds the one result shape of the resolution contract. */
    static ReferenceToolResult ofResolution(ReferenceToolContract contract, ReferenceResolutionToolOutput output) {
        return new ReferenceToolResult(
                contract.toolId(), contract.toolVersion(), ReferenceToolOutputKind.RESOLUTION, null, output);
    }

    /** A bounded diagnostic: the contract identity and the answer shape, never an answer value. */
    @Override
    public String toString() {
        return "ReferenceToolResult[toolId=" + toolId
                + ", toolVersion=" + toolVersion
                + ", outputKind=" + outputKind
                + ']';
    }
}