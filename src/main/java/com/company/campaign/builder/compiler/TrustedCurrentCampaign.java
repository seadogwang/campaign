package com.company.campaign.builder.compiler;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.journey.graph.JourneyTopology;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The trusted Current state a compile merges against, supplied by the host only.
 *
 * <p>It carries exact tenant/program ownership, the campaign identifier and revision, the editable Draft
 * identity and revision, an optional immutable Published fork base, and the complete ordered current
 * component list. Nothing here is read from a model-controlled payload: it is resolved server-side from the
 * Campaign Draft, and the compiler compares it with the patch and the trusted context before any merge.
 *
 * <p>A CREATE compile has no Current at all. An EDIT compile requires one, and a Published version is never
 * an editing target: {@link #draft()} must identify an editable Draft that is not the published identity.
 */
public record TrustedCurrentCampaign(
        String tenantId,
        String programId,
        String campaignId,
        long campaignRevision,
        DraftIdentity draft,
        PublishedIdentity sourcePublished,
        List<ResolvedComponent> components,
        JourneyTopology journeyTopology) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    /**
     * The accepted T15/T20 shape: a Current campaign that honestly carries no frozen Journey topology.
     *
     * <p>It is kept so every accepted call site keeps compiling. A Current built through it simply states that
     * the Campaign holds no topology, so an omission that inherits is an honest absence rather than a silently
     * fabricated empty graph.
     */
    public TrustedCurrentCampaign(
            String tenantId,
            String programId,
            String campaignId,
            long campaignRevision,
            DraftIdentity draft,
            PublishedIdentity sourcePublished,
            List<ResolvedComponent> components) {
        this(tenantId, programId, campaignId, campaignRevision, draft, sourcePublished, components, null);
    }

    public TrustedCurrentCampaign {
        requireIdentifier(tenantId, "tenantId");
        requireIdentifier(programId, "programId");
        requireIdentifier(campaignId, "campaignId");
        if (campaignRevision < 1) {
            throw new IllegalArgumentException("campaignRevision must be positive");
        }
        Objects.requireNonNull(draft, "draft");
        if (sourcePublished != null && sourcePublished.publishedId().equals(draft.draftId())) {
            // Editing published content in place is not representable: a fork must have its own Draft identity.
            // Revision numbers are not identity: a Draft and a Published version may legitimately share a
            // revision counter, so only the identities themselves are compared.
            throw new IllegalArgumentException("a Published source may only be recorded as an immutable fork base");
        }
        List<ResolvedComponent> copy = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (components != null) {
            for (ResolvedComponent component : components) {
                Objects.requireNonNull(component, "component");
                if (!ids.add(component.logicalId())) {
                    throw new IllegalArgumentException("a current campaign must not repeat one logicalId");
                }
                copy.add(component);
            }
        }
        components = List.copyOf(copy);
    }

    /** True when {@code logicalId} is a component of this Current campaign. */
    public boolean owns(String logicalId) {
        return components.stream().anyMatch(component -> component.logicalId().equals(logicalId));
    }

    /** True when this Current campaign carries a frozen Journey topology an omission inherits. */
    public boolean hasJourneyTopology() {
        return journeyTopology != null;
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a bounded canonical identifier");
        }
    }

    @Override
    public String toString() {
        return "TrustedCurrentCampaign[campaignId=" + campaignId
                + ", campaignRevision=" + campaignRevision
                + ", draftRevision=" + draft.revision()
                + ", forked=" + (sourcePublished != null)
                + ", components=" + components.size()
                + ']';
    }
}