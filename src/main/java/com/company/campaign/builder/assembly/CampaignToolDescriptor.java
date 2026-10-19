package com.company.campaign.builder.assembly;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.regex.Pattern;

/**
 * One server-owned Campaign Tool descriptor: its exact identity, owner, order, purpose and bounds.
 *
 * <p>Every semantic field is covered by {@link #descriptorHash()}, so two descriptors that differ in any
 * declared fact hash differently and a catalog pin can never describe the wrong Tool surface.
 *
 * <p>{@code available} and {@code modelCallable} are explicit facts, not conventions: a planned Tool is
 * representable with {@code available == false} and the invoker refuses it before any handler runs, and a
 * host-only bridge is {@code modelCallable == false} so it can never be reached from a model payload. There is
 * deliberately no field that could describe a mutation, persistence or runtime action.
 */
public record CampaignToolDescriptor(
        String toolId,
        String toolVersion,
        String owningSkillId,
        int rank,
        CampaignToolKind kind,
        String purposeCode,
        int maxInputBytes,
        int maxResultBytes,
        boolean modelCallable,
        boolean available) {

    /** Absolute bound on the canonical bytes of one Tool input. */
    public static final int MAX_INPUT_BYTES = 65_536;

    /** Absolute bound on the canonical bytes of one Tool result. */
    public static final int MAX_RESULT_BYTES = 65_536;

    /** Absolute bound on the stable semantic references one Tool outcome may carry. */
    public static final int MAX_RESULT_ITEMS = 512;

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern SKILL = Pattern.compile("^[a-z][a-z0-9.-]{0,63}$");
    private static final Pattern CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);

    public CampaignToolDescriptor {
        if (toolId == null || !IDENTIFIER.matcher(toolId).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_UNKNOWN, "A Tool descriptor requires a bounded identifier.");
        }
        if (toolVersion == null || !IDENTIFIER.matcher(toolVersion).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_UNKNOWN, "A Tool descriptor requires a bounded version.");
        }
        if (owningSkillId == null || !SKILL.matcher(owningSkillId).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_WRONG_SKILL, "A Tool descriptor requires an owning Skill.");
        }
        if (rank < 0) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_UNKNOWN, "A Tool descriptor requires a non-negative rank.");
        }
        if (kind == null) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_UNKNOWN, "A Tool descriptor requires a closed purpose kind.");
        }
        if (purposeCode == null || !CODE.matcher(purposeCode).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_UNKNOWN, "A Tool descriptor requires a bounded purpose code.");
        }
        if (maxInputBytes < 1 || maxInputBytes > MAX_INPUT_BYTES) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_INPUT_LIMIT_EXCEEDED, "A Tool descriptor input bound is invalid.");
        }
        if (maxResultBytes < 1 || maxResultBytes > MAX_RESULT_BYTES) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_LIMIT_EXCEEDED, "A Tool descriptor result bound is invalid.");
        }
        if (modelCallable && !available) {
            // A Tool a model may call must have an implementation; the two facts cannot disagree.
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_UNAVAILABLE,
                    "A model-callable Tool descriptor must be available.");
        }
    }

    /** The canonical UTF-8 bytes covering every semantic field of this descriptor. */
    public byte[] canonicalBytes() {
        return CANONICAL_JSON.write(this);
    }

    /** The content hash covering every semantic field of this descriptor. */
    public ContentHash descriptorHash() {
        return CANONICAL_JSON.hash(this);
    }

    @Override
    public String toString() {
        return "CampaignToolDescriptor[toolId=" + toolId
                + ", toolVersion=" + toolVersion
                + ", skill=" + owningSkillId
                + ", rank=" + rank
                + ", kind=" + kind
                + ", modelCallable=" + modelCallable
                + ", available=" + available + ']';
    }
}