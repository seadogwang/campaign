package com.company.campaign.builder.assembly;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;

/**
 * The bounded inspection view of one ephemeral Campaign assembly revision.
 *
 * <p>It contains only Skill/component state, bounded codes, accepted artifact hashes and missing-dependency
 * codes. It deliberately carries no capability payload, no raw model or Provider output, no template body, no
 * reward secret, no credential, no member identifier and no SQL. A host may project it to a builder UI without
 * exposing the Proposal material itself.
 */
public record CampaignAssemblyInspection(
        long revision,
        List<CampaignComponentView> components,
        List<CampaignSkillState> skills,
        List<String> missingDependencyCodes,
        ContentHash inspectionHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public CampaignAssemblyInspection {
        if (revision < 0) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.REQUEST_INVALID, "An inspection revision must not be negative.");
        }
        components = components == null ? List.of() : List.copyOf(new ArrayList<>(components));
        skills = skills == null ? List.of() : List.copyOf(new ArrayList<>(skills));
        missingDependencyCodes = missingDependencyCodes == null
                ? List.of()
                : List.copyOf(new ArrayList<>(missingDependencyCodes));
    }

    /** Builds one bounded inspection view, hashing exactly the facts a reader can observe. */
    public static CampaignAssemblyInspection of(
            long revision,
            List<CampaignStagedComponent> staged,
            List<CampaignSkillState> skills,
            List<String> missingDependencyCodes) {
        List<CampaignComponentView> views = new ArrayList<>();
        for (CampaignStagedComponent component : staged) {
            views.add(CampaignComponentView.of(component));
        }
        List<CampaignComponentView> componentViews = List.copyOf(views);
        List<CampaignSkillState> skillViews = skills == null ? List.of() : List.copyOf(skills);
        List<String> codes = missingDependencyCodes == null ? List.of() : List.copyOf(missingDependencyCodes);
        ContentHash hash = CANONICAL_JSON.hash(new Material(revision, componentViews, skillViews, codes));
        return new CampaignAssemblyInspection(revision, componentViews, skillViews, codes, hash);
    }

    private record Material(
            long revision,
            List<CampaignComponentView> components,
            List<CampaignSkillState> skills,
            List<String> missingDependencyCodes) {
    }
}