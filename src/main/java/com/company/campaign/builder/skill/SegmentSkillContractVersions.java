package com.company.campaign.builder.skill;

import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.builder.tool.SegmentToolVersions;
import com.company.campaign.query.plan.PlanVersions;
import com.company.campaign.segment.canonical.CanonicalizerVersions;

/**
 * The one accepted version of every contract a T11 Segment Skill pins.
 *
 * <p>A Skill is only reproducible if it names the exact versions it was authored against, so the loader
 * refuses an unknown pin instead of assuming compatibility. Where the accepted subsystem already publishes a
 * version constant this class reuses it rather than restating it, so the pin cannot drift from the code.
 */
public final class SegmentSkillContractVersions {

    /** The formal Agent adapter contract version. */
    public static final String AGENT_CONTRACT_VERSION = "segment-agent-contract/1.0";

    /** The accepted T10 Intent schema version. */
    public static final String INTENT_VERSION = ContractVersions.V1_3.value();

    /** The accepted T05 Segment DSL schema version. */
    public static final String DSL_VERSION = ContractVersions.V1_3.value();

    /** The accepted T07 canonicalizer version. */
    public static final String CANONICALIZER_VERSION = CanonicalizerVersions.CANONICALIZER_VERSION;

    /** The accepted T10 deterministic resolver version. */
    public static final String RESOLVER_VERSION = "segment-intent-resolver/1.0";

    /** The accepted T07 validator version. */
    public static final String VALIDATOR_VERSION = "segment-validator/1.0";

    /** The accepted T08 planner version. */
    public static final String PLANNER_VERSION = PlanVersions.PLANNER_VERSION;

    /** The accepted T09C Preview contract version. */
    public static final String PREVIEW_CONTRACT_VERSION = "segment-preview/1.0";

    /** The accepted server-owned Tool schema version. */
    public static final String TOOL_SCHEMA_VERSION = SegmentToolVersions.CURRENT_TOOL_SCHEMA;

    private SegmentSkillContractVersions() {
    }
}