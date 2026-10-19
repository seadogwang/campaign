package com.company.campaign.builder.campaignagent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One capability-level decision in a Campaign conversation.
 *
 * <p>It records whether a capability (SEGMENT, EVENT, MESSAGE, PROMOTION, FLOW, SCHEDULE) is
 * required, who decided, and which real catalog resources are bound to it. It is the structured
 * companion to {@link CampaignRequirementBrief} — the brief accumulates requirement text facts,
 * and the manifest records per-capability decisions and resource bindings.
 *
 * <p>Invariants enforced at construction:
 * <ul>
 *   <li>UNCERTAIN and NOT_REQUIRED decisions must have zero selected resource codes.</li>
 *   <li>A REQUIRED decision for EVENT, MESSAGE or PROMOTION may carry resource codes;
 *       each code is validated against the catalog by the host, never by this record.</li>
 *   <li>reasonCode and evidence are bounded text; evidence is a short quote from user input.</li>
 * </ul>
 *
 * <p>This record is immutable. The manifest list in a session only grows: a decision once made
 * is never removed, though it may be superseded by a later turn's decision of the same capability.
 * A USER decision may not be overridden by a subsequent MODEL decision.
 */
public record CampaignComponentManifest(
        CampaignCapability capability,
        UsageDecision usageDecision,
        String reasonCode,
        String evidence,
        List<String> selectedResourceCodes,
        DecisionSource decisionSource
) {

    public CampaignComponentManifest {
        if (capability == null) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "manifest",
                    "A component manifest requires its capability code.");
        }
        if (usageDecision == null) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "manifest",
                    "A component manifest requires its usage decision.");
        }
        if (decisionSource == null) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "manifest",
                    "A component manifest requires its decision source.");
        }
        reasonCode = reasonCode == null ? "" : reasonCode;
        if (reasonCode.length() > 128) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "manifestReasonCode",
                    "A manifest reason code must be bounded.");
        }
        evidence = evidence == null ? "" : evidence;
        if (evidence.length() > CampaignAgentLimits.MAX_EVIDENCE_LENGTH) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "manifestEvidence",
                    "Manifest evidence text must be bounded.");
        }
        Set<String> unique = new LinkedHashSet<>();
        if (selectedResourceCodes != null) {
            for (String code : selectedResourceCodes) {
                if (code != null && !code.isBlank()) {
                    unique.add(code);
                }
            }
        }
        if (usageDecision.forbidsResources() && !unique.isEmpty()) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "manifestResources",
                    "An UNCERTAIN or NOT_REQUIRED capability must not carry resource bindings.");
        }
        if (unique.size() > CampaignAgentLimits.MAX_SELECTED_RESOURCES) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_LIMIT_EXCEEDED, "manifestResources",
                    "A manifest exceeds its resource binding bound.");
        }
        selectedResourceCodes = List.copyOf(new ArrayList<>(unique));
    }

    /**
     * True when this manifest carries a REQUIRED decision for a resource-binding capability
     * and has at least one bound resource code.
     */
    public boolean resourceBindingComplete() {
        return usageDecision == UsageDecision.REQUIRED
                && capability.requiresResourceBinding()
                && !selectedResourceCodes.isEmpty();
    }

    /**
     * True when a USER or SYSTEM decision with the given usage decision would supersede this one.
     *
     * <p>A MODEL decision is always superseded. A USER decision is only superseded by an
     * explicit user correction (another USER or SYSTEM decision). A SYSTEM decision is only
     * superseded by another SYSTEM decision or a USER decision.
     */
    public boolean supersededBy(UsageDecision newDecision, DecisionSource newSource) {
        if (newSource == DecisionSource.USER) {
            return decisionSource == DecisionSource.MODEL || decisionSource == DecisionSource.USER;
        }
        if (newSource == DecisionSource.SYSTEM) {
            return decisionSource != DecisionSource.USER;
        }
        return decisionSource == DecisionSource.MODEL;
    }

    /** Returns a copy of this manifest with the given resource codes, preserving every other field. */
    public CampaignComponentManifest withResourceCodes(List<String> codes) {
        return new CampaignComponentManifest(
                capability, usageDecision, reasonCode, evidence, codes, decisionSource);
    }

    /** Returns a copy of this manifest with the given usage decision and source, preserving other fields. */
    public CampaignComponentManifest withDecision(UsageDecision decision, DecisionSource source) {
        return new CampaignComponentManifest(
                capability, decision, reasonCode, evidence,
                decision.forbidsResources() ? List.of() : selectedResourceCodes,
                source);
    }

    /** A bounded diagnostic: the capability, decision and resource count, never a value. */
    @Override
    public String toString() {
        return "CampaignComponentManifest[capability=" + capability
                + ", usageDecision=" + usageDecision
                + ", decisionSource=" + decisionSource
                + ", resources=" + selectedResourceCodes.size() + ']';
    }
}