package com.company.campaign.builder.assembly;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;

/**
 * The immutable inspection view of one ephemeral Campaign assembly revision.
 *
 * <p>It carries only the revision, the staged components, the per-Skill state and the missing-dependency codes
 * the workspace reports. It never carries raw model output or restricted data, and {@link #snapshotHash()}
 * covers exactly the material a reader can observe, so two snapshots with equal material always hash and compare
 * equal.
 */
public record CampaignAssemblySnapshot(
        long revision,
        List<CampaignStagedComponent> components,
        List<CampaignSkillState> skills,
        List<String> missingDependencyCodes,
        ContentHash snapshotHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public CampaignAssemblySnapshot {
        if (revision < 0) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.REQUEST_INVALID, "A snapshot revision must not be negative.");
        }
        components = components == null ? List.of() : List.copyOf(new ArrayList<>(components));
        skills = skills == null ? List.of() : List.copyOf(new ArrayList<>(skills));
        missingDependencyCodes = missingDependencyCodes == null
                ? List.of()
                : List.copyOf(new ArrayList<>(missingDependencyCodes));
    }

    /** Publishes a snapshot, computing the canonical hash of its observable material. */
    public static CampaignAssemblySnapshot of(
            long revision,
            List<CampaignStagedComponent> components,
            List<CampaignSkillState> skills,
            List<String> missingDependencyCodes) {
        List<CampaignStagedComponent> componentCopy = components == null ? List.of() : List.copyOf(components);
        List<CampaignSkillState> skillCopy = skills == null ? List.of() : List.copyOf(skills);
        List<String> codeCopy = missingDependencyCodes == null
                ? List.of()
                : List.copyOf(missingDependencyCodes);
        ContentHash hash = CANONICAL_JSON.hash(new Material(revision, componentCopy, skillCopy, codeCopy));
        return new CampaignAssemblySnapshot(revision, componentCopy, skillCopy, codeCopy, hash);
    }

    /** The staged component under one identity, or null when this revision holds none. */
    public CampaignStagedComponent component(String componentId) {
        for (CampaignStagedComponent component : components) {
            if (component.componentId().equals(componentId)) {
                return component;
            }
        }
        return null;
    }

    /** The state of one Skill, or a MISSING state when this revision holds none for it. */
    public CampaignSkillState skill(String skillId) {
        for (CampaignSkillState state : skills) {
            if (state.skillId().equals(skillId)) {
                return state;
            }
        }
        return new CampaignSkillState(skillId, CampaignComponentState.MISSING, null, List.of());
    }

    private record Material(
            long revision,
            List<CampaignStagedComponent> components,
            List<CampaignSkillState> skills,
            List<String> missingDependencyCodes) {
    }
}