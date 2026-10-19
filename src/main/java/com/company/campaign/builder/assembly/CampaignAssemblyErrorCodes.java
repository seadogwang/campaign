package com.company.campaign.builder.assembly;

/**
 * Stable machine codes for the Campaign assembly workspace and the closed Campaign Tool boundary.
 *
 * <p>Every value is a bounded machine code a host, API error envelope or later task branches on; no code ever
 * echoes a rejected Tool identifier, payload value, capability payload, template body, reward secret, Provider
 * fact or thrown cause.
 */
public final class CampaignAssemblyErrorCodes {

    // ---- closed Tool vocabulary -------------------------------------------------------------------

    /** The requested Tool identifier is not part of the closed server-owned Campaign catalog. */
    public static final String TOOL_UNKNOWN = "CAMPAIGN_TOOL_UNKNOWN";

    /** The requested Tool identifier names a host-only or unsafe capability and is never model-callable. */
    public static final String TOOL_FORBIDDEN_CAPABILITY = "CAMPAIGN_TOOL_FORBIDDEN_CAPABILITY";

    /** The Tool is a server-owned descriptor, but it is not in this build's implemented, available surface. */
    public static final String TOOL_UNAVAILABLE = "CAMPAIGN_TOOL_UNAVAILABLE";

    /** The Tool is available to the host but is deliberately not model-callable. */
    public static final String TOOL_NOT_MODEL_CALLABLE = "CAMPAIGN_TOOL_NOT_MODEL_CALLABLE";

    /** The Tool call names a Tool the selected Skill does not own. */
    public static final String TOOL_WRONG_SKILL = "CAMPAIGN_TOOL_WRONG_SKILL";

    /** The Tool call names a Tool version that is not the server-owned version. */
    public static final String TOOL_VERSION_MISMATCH = "CAMPAIGN_TOOL_VERSION_MISMATCH";

    /** The Tool call repeats a call identifier or a Tool identifier already used in this run. */
    public static final String TOOL_DUPLICATE_CALL = "CAMPAIGN_TOOL_DUPLICATE_CALL";

    /** The Tool call is ordered before a Tool the run has already used. */
    public static final String TOOL_OUT_OF_ORDER = "CAMPAIGN_TOOL_OUT_OF_ORDER";

    /** The run has already reached a terminal disposition. */
    public static final String TOOL_POST_TERMINAL = "CAMPAIGN_TOOL_POST_TERMINAL";

    /** The run would exceed its catalog-bounded Tool-call count. */
    public static final String TOOL_CALL_LIMIT_EXCEEDED = "CAMPAIGN_TOOL_CALL_LIMIT_EXCEEDED";

    /** The canonical Tool request exceeds the descriptor's bounded input size. */
    public static final String TOOL_INPUT_LIMIT_EXCEEDED = "CAMPAIGN_TOOL_INPUT_LIMIT_EXCEEDED";

    /** The canonical Tool result exceeds the descriptor's bounded result size. */
    public static final String TOOL_RESULT_LIMIT_EXCEEDED = "CAMPAIGN_TOOL_RESULT_LIMIT_EXCEEDED";

    /** No host implementation is registered for an available Tool. */
    public static final String TOOL_NOT_IMPLEMENTED = "CAMPAIGN_TOOL_NOT_IMPLEMENTED";

    /** The Tool schema version is not the current server-owned version. */
    public static final String TOOL_SCHEMA_STALE = "CAMPAIGN_TOOL_SCHEMA_STALE";

    /** The Tool call or its typed request is malformed or incoherent. */
    public static final String TOOL_RESULT_INCOHERENT = "CAMPAIGN_TOOL_RESULT_INCOHERENT";

    /** The Tool request arguments name a reserved trusted, host-only or unsafe field. */
    public static final String TOOL_ARGUMENT_INVALID = "CAMPAIGN_TOOL_ARGUMENT_INVALID";

    /** The Tool implementation refused the request with a bounded code of its own. */
    public static final String TOOL_EXECUTION_FAILED = "CAMPAIGN_TOOL_EXECUTION_FAILED";

    /** A handler published a host-private artifact that does not belong to this exact call and outcome. */
    public static final String TOOL_ARTIFACT_INCOHERENT = "CAMPAIGN_TOOL_ARTIFACT_INCOHERENT";

    /** A caller asked for the host-private artifact of a call that never produced one. */
    public static final String TOOL_ARTIFACT_MISSING = "CAMPAIGN_TOOL_ARTIFACT_MISSING";

    /** A caller asked for the host-private artifact of a different call, Tool or catalog. */
    public static final String TOOL_ARTIFACT_MISMATCH = "CAMPAIGN_TOOL_ARTIFACT_MISMATCH";

    /** The host run does not hold the evidence an available Tool requires. */
    public static final String TOOL_EVIDENCE_MISSING = "CAMPAIGN_TOOL_EVIDENCE_MISSING";

    /** The requested catalog reference is absent or not enabled/runtime-compatible. */
    public static final String TOOL_RESOURCE_UNAVAILABLE = "CAMPAIGN_TOOL_RESOURCE_UNAVAILABLE";

    /** The supplied capability variables are absent, extra, mistyped or over their declared bound. */
    public static final String TOOL_VARIABLES_INVALID = "CAMPAIGN_TOOL_VARIABLES_INVALID";

    /** The supplied reward quantity or governance ceiling is outside its bounded domain. */
    public static final String TOOL_EXPOSURE_INVALID = "CAMPAIGN_TOOL_EXPOSURE_INVALID";

    /** The supplied Schedule or Governance declaration was refused by its accepted capability pack. */
    public static final String TOOL_DECLARATION_INVALID = "CAMPAIGN_TOOL_DECLARATION_INVALID";

    /** A supplied Journey authoring graph was refused, or did not form a valid Journey. */
    public static final String TOOL_GRAPH_INVALID = "CAMPAIGN_TOOL_GRAPH_INVALID";

    // ---- assembly workspace -----------------------------------------------------------------------

    /** The accepted Skill artifact is not a coherent, fully bound staged component. */
    public static final String ARTIFACT_INVALID = "CAMPAIGN_ASSEMBLY_ARTIFACT_INVALID";

    /** The artifact was produced by a different conversation or run. */
    public static final String ARTIFACT_WRONG_RUN = "CAMPAIGN_ASSEMBLY_ARTIFACT_WRONG_RUN";

    /** The artifact was admitted under a different Campaign Tool catalog. */
    public static final String ARTIFACT_CATALOG_MISMATCH = "CAMPAIGN_ASSEMBLY_ARTIFACT_CATALOG_MISMATCH";

    /** The artifact names a Skill this build does not own, or an unknown Skill version. */
    public static final String ARTIFACT_WRONG_SKILL = "CAMPAIGN_ASSEMBLY_ARTIFACT_WRONG_SKILL";

    /** The artifact's capability is not owned by the Skill that produced it. */
    public static final String ARTIFACT_OWNERSHIP_INVALID = "CAMPAIGN_ASSEMBLY_ARTIFACT_OWNERSHIP_INVALID";

    /** The artifact names a discovery, partial or unavailable Tool that cannot stage a component. */
    public static final String ARTIFACT_TOOL_NOT_FINAL = "CAMPAIGN_ASSEMBLY_ARTIFACT_TOOL_NOT_FINAL";

    /** The workspace expected a different revision than the one the caller supplied. */
    public static final String STALE_REVISION = "CAMPAIGN_ASSEMBLY_STALE_REVISION";

    /** The component identity is already staged under a different capability or Skill. */
    public static final String IDENTITY_CONFLICT = "CAMPAIGN_ASSEMBLY_IDENTITY_CONFLICT";

    /** A DELETE or MOVE names a component identity this workspace has not staged. */
    public static final String UNRESOLVED_TARGET = "CAMPAIGN_ASSEMBLY_UNRESOLVED_TARGET";

    /** The staged operation combination for one identity is contradictory. */
    public static final String OPERATION_CONFLICT = "CAMPAIGN_ASSEMBLY_OPERATION_CONFLICT";

    /** The workspace already holds the bounded maximum number of staged components. */
    public static final String COMPONENT_LIMIT_EXCEEDED = "CAMPAIGN_ASSEMBLY_COMPONENT_LIMIT_EXCEEDED";

    /** The requested assembly step is malformed. */
    public static final String REQUEST_INVALID = "CAMPAIGN_ASSEMBLY_REQUEST_INVALID";

    // ---- dependency inspection --------------------------------------------------------------------

    /** A staged promotion component has no staged governance limit to bound it. */
    public static final String MISSING_GOVERNANCE_LIMIT = "CAMPAIGN_ASSEMBLY_MISSING_GOVERNANCE_LIMIT";

    /** A staged Flow component references an identity this workspace has not staged. */
    public static final String FLOW_REFERENCE_UNRESOLVED = "CAMPAIGN_ASSEMBLY_FLOW_REFERENCE_UNRESOLVED";

    /** A staged component references a catalog identity this workspace has not staged. */
    public static final String COMPONENT_REFERENCE_UNRESOLVED = "CAMPAIGN_ASSEMBLY_REFERENCE_UNRESOLVED";

    // ---- adapters ---------------------------------------------------------------------------------

    /** The adapter was handed an admission that is not a final accepted artifact. */
    public static final String ADAPTER_NOT_ELIGIBLE = "CAMPAIGN_ASSEMBLY_ADAPTER_NOT_ELIGIBLE";

    private CampaignAssemblyErrorCodes() {
    }
}
