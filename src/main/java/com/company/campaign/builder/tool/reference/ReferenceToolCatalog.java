package com.company.campaign.builder.tool.reference;

import com.company.campaign.builder.tool.SegmentToolIds;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The closed, host-owned registry of the read-only Existing Audience Reference Tools.
 *
 * <p>Exactly two contracts are registered, and both are pure reads over the host-owned discovery directory:
 *
 * <ol>
 *   <li>{@link #DISCOVER} answers which audiences a caller may reference that one bounded untrusted candidate
 *       text matches, as bounded safe summaries;</li>
 *   <li>{@link #RESOLVE} answers which single immutable published version one Existing Audience Reference
 *       resolves to, or the one bounded clarification, denial or unavailable outcome.</li>
 * </ol>
 *
 * <p>Registration is server-owned: there is no register, install, define, override or put method anywhere on
 * this type, so neither a Skill resource, a client nor a model can widen the surface. A name outside the two
 * registered identifiers is refused, and a name that attempts a host-only mutation is refused
 * <em>before</em> the unknown-identifier rule so an attempted Save/Apply/Publish/retain/delete is reported as
 * the dangerous capability it is rather than as a merely unknown Tool.
 *
 * <p>The refusal reuses the accepted T11 normalize-and-match rule ({@link SegmentToolIds}), so the reference
 * surface cannot drift into admitting a mutation name the agent boundary already refuses.
 */
public final class ReferenceToolCatalog {

    /** Which audiences one bounded candidate text may resolve to; a pure read, never a write. */
    public static final String DISCOVER = "audience.reference.discover";

    /** Which immutable published version one reference resolves to; a pure read, never a write. */
    public static final String RESOLVE = "audience.reference.resolve";

    /** The exact version every registered reference Tool is pinned at. */
    public static final String TOOL_VERSION = "1.0";

    private static final Map<String, ReferenceToolContract> CONTRACTS = register();

    private ReferenceToolCatalog() {
    }

    private static Map<String, ReferenceToolContract> register() {
        Map<String, ReferenceToolContract> contracts = new LinkedHashMap<>();
        contracts.put(DISCOVER, new ReferenceToolContract(
                DISCOVER, TOOL_VERSION, ReferenceToolOutputKind.DISCOVERY, ReferenceToolContract.MAX_RESULT_ITEMS));
        contracts.put(RESOLVE, new ReferenceToolContract(
                RESOLVE, TOOL_VERSION, ReferenceToolOutputKind.RESOLUTION, 1));
        return Map.copyOf(contracts);
    }

    /** The two registered read-only contracts, in canonical identifier order. */
    public static List<ReferenceToolContract> all() {
        return List.of(CONTRACTS.get(DISCOVER), CONTRACTS.get(RESOLVE));
    }

    /** Every registered identifier. */
    public static List<String> registeredIds() {
        return List.of(DISCOVER, RESOLVE);
    }

    /** True only when the identifier is one of the two registered read-only Tools. */
    public static boolean isRegistered(String toolId) {
        return toolId != null && CONTRACTS.containsKey(toolId);
    }

    /**
     * Resolves one identifier to its registered contract, or refuses it.
     *
     * <p>The forbidden-capability rule runs first, so a mutation name never reaches the unknown-identifier
     * branch and is always reported as a forbidden capability rather than as an unknown Tool.
     */
    public static ReferenceToolContract require(String toolId) {
        if (SegmentToolIds.attemptsForbiddenCapability(toolId)) {
            throw ReferenceToolException.invalid(
                    ReferenceToolErrorCodes.REFERENCE_TOOL_FORBIDDEN_CAPABILITY,
                    "The identifier names a host-only capability that no read-only reference Tool may expose");
        }
        ReferenceToolContract contract = toolId == null ? null : CONTRACTS.get(toolId);
        if (contract == null) {
            throw ReferenceToolException.invalid(
                    ReferenceToolErrorCodes.REFERENCE_TOOL_UNKNOWN,
                    "The identifier is not one of the registered read-only reference Tools");
        }
        return contract;
    }
}