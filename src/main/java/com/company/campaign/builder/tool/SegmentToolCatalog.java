package com.company.campaign.builder.tool;

import com.company.campaign.builder.skill.SegmentSkillDefinition;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The closed, host-owned Tool catalog of one Skill.
 *
 * <p>It is derived exclusively from a validated {@link SegmentSkillDefinition}: the allowlist, the server-owned
 * Tool versions and the Skill bounds. There is no method to register a Tool, choose an implementation or widen
 * the allowlist, so a model — or any caller holding only a model payload — cannot change what is executable.
 * The catalog is immutable and a run creates a fresh {@link SegmentToolInvoker} from it, so no mutable "latest"
 * registry can influence a run.
 */
public final class SegmentToolCatalog {

    private final String skillId;
    private final String skillVersion;
    private final ContentHash skillHash;
    private final List<SegmentToolDescriptor> ordered;
    private final Map<String, SegmentToolDescriptor> byId;
    private final int maxToolCalls;
    private final int maxToolPayloadBytes;

    private SegmentToolCatalog(
            String skillId,
            String skillVersion,
            ContentHash skillHash,
            List<SegmentToolDescriptor> ordered,
            int maxToolCalls,
            int maxToolPayloadBytes) {
        this.skillId = skillId;
        this.skillVersion = skillVersion;
        this.skillHash = skillHash;
        this.ordered = List.copyOf(ordered);
        Map<String, SegmentToolDescriptor> map = new LinkedHashMap<>();
        for (SegmentToolDescriptor descriptor : this.ordered) {
            map.put(descriptor.toolId(), descriptor);
        }
        this.byId = Map.copyOf(map);
        this.maxToolCalls = maxToolCalls;
        this.maxToolPayloadBytes = maxToolPayloadBytes;
    }

    /** Builds the one catalog a validated Skill admits. */
    public static SegmentToolCatalog fromSkill(SegmentSkillDefinition skill) {
        if (skill == null) {
            throw SegmentToolException.invalid(
                    SegmentToolErrorCodes.TOOL_NOT_ALLOWED, "A Tool catalog requires a validated Skill");
        }
        List<SegmentToolDescriptor> descriptors = new ArrayList<>(skill.tools().size());
        for (SegmentSkillDefinition.ToolPin pin : skill.tools()) {
            descriptors.add(new SegmentToolDescriptor(
                    pin.toolId(),
                    pin.toolVersion(),
                    pin.rank(),
                    pin.maxPayloadBytes(),
                    SegmentToolDescriptor.MAX_RESULT_ITEMS));
        }
        return new SegmentToolCatalog(
                skill.skillId(), skill.skillVersion(), skill.canonicalHash(), descriptors,
                skill.limits().maxToolCalls(),
                skill.limits().maxToolPayloadBytes());
    }

    /** The exact Skill this catalog was derived from. */
    public String skillId() {
        return skillId;
    }

    /** The exact Skill version this catalog was derived from. */
    public String skillVersion() {
        return skillVersion;
    }

    /** The canonical hash of the exact Skill this catalog was derived from. */
    public ContentHash skillHash() {
        return skillHash;
    }

    /** The allowed Tool identifiers in the Skill's declared order. */
    public List<String> allowedToolIds() {
        List<String> ids = new ArrayList<>(ordered.size());
        for (SegmentToolDescriptor descriptor : ordered) {
            ids.add(descriptor.toolId());
        }
        return List.copyOf(ids);
    }

    /** The allowed Tool descriptors in the Skill's declared order. */
    public List<SegmentToolDescriptor> ordered() {
        return ordered;
    }

    /** The descriptor for one allowed Tool, or null when the Skill does not allow it. */
    public SegmentToolDescriptor descriptor(String toolId) {
        return byId.get(toolId);
    }

    /** True when the exact Skill allows the Tool identifier. */
    public boolean allows(String toolId) {
        return byId.containsKey(toolId);
    }

    /** The Skill-bounded maximum number of Tool calls in one run. */
    public int maxToolCalls() {
        return maxToolCalls;
    }

    /** The Skill-bounded maximum canonical Tool payload size in bytes. */
    public int maxToolPayloadBytes() {
        return maxToolPayloadBytes;
    }
}