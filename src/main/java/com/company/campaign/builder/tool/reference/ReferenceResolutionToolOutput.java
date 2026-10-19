package com.company.campaign.builder.tool.reference;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.segment.resolution.AudienceReferenceChoice;
import com.company.campaign.segment.resolution.AudienceReferenceResolution;
import com.company.campaign.segment.resolution.QuestionCode;
import com.company.campaign.segment.resolution.ResolutionLimits;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;

/**
 * The bounded, non-disclosing answer of the read-only resolution Tool.
 *
 * <p>It is the safe projection of one {@link AudienceReferenceResolution} onto exactly the facts a caller may
 * see: the disposition, the one exact immutable pin when — and only when — one was accepted, the safe label of
 * that version, whether the request used dynamic wording and whether the pin therefore had to be disclosed
 * explicitly, the already-safe clarification choices, the stable question codes, how many candidates were
 * visible, and the evidence hash that proves which resolution this answer is.
 *
 * <p>There is deliberately no candidate text, no member identifier, no member count, no owner, program,
 * DataScope, permission, dependency or Gate fact, and no mutable {@code current}/{@code latest} pointer. The
 * accepted shape invariants of the accepted resolution are re-proved here, so a contradictory projection — a
 * pin without an acceptance, or a choice offered beside one — cannot be built.
 */
public record ReferenceResolutionToolOutput(
        String referenceId,
        Disposition disposition,
        AudienceVersionRef resolved,
        String resolvedLabel,
        boolean dynamicWording,
        boolean pinnedFromDynamicWording,
        List<AudienceReferenceChoice> choices,
        List<QuestionCode> questionCodes,
        int candidateCount,
        ContentHash evidenceHash
) {

    public ReferenceResolutionToolOutput {
        if (referenceId == null || disposition == null) {
            throw ReferenceToolException.invalid(
                    ReferenceToolErrorCodes.REFERENCE_TOOL_INCONSISTENT,
                    "A resolution answer requires its identity and disposition");
        }
        if (candidateCount < 0) {
            throw ReferenceToolException.invalid(
                    ReferenceToolErrorCodes.REFERENCE_TOOL_INCONSISTENT,
                    "A resolution answer candidate count must not be negative");
        }
        choices = choices == null ? List.of() : List.copyOf(new ArrayList<>(choices));
        questionCodes = questionCodes == null ? List.of() : List.copyOf(new ArrayList<>(questionCodes));
        if (choices.size() > ResolutionLimits.MAX_CLARIFICATION_OPTIONS) {
            throw ReferenceToolException.invalid(
                    ReferenceToolErrorCodes.REFERENCE_TOOL_OUTPUT_LIMIT_EXCEEDED,
                    "A resolution answer exceeds the clarification choice limit");
        }
        boolean accepted = disposition.isResolved();
        if (accepted != (resolved != null)
                || accepted != (resolvedLabel != null)
                || accepted != (evidenceHash != null)) {
            throw ReferenceToolException.invalid(
                    ReferenceToolErrorCodes.REFERENCE_TOOL_INCONSISTENT,
                    "Only an accepted resolution answer may name a pin, a label and its evidence");
        }
        if (accepted && (!choices.isEmpty() || !questionCodes.isEmpty())) {
            throw ReferenceToolException.invalid(
                    ReferenceToolErrorCodes.REFERENCE_TOOL_INCONSISTENT,
                    "An accepted resolution answer must not offer a choice");
        }
        if (disposition == Disposition.NEEDS_INPUT && questionCodes.isEmpty()) {
            // A needs-input answer exists to ask something; an answer that asks nothing is not representable.
            throw ReferenceToolException.invalid(
                    ReferenceToolErrorCodes.REFERENCE_TOOL_INCONSISTENT,
                    "A needs-input resolution answer requires its stable question codes");
        }
        if (pinnedFromDynamicWording && (!accepted || !dynamicWording)) {
            throw ReferenceToolException.invalid(
                    ReferenceToolErrorCodes.REFERENCE_TOOL_INCONSISTENT,
                    "Only a dynamic-worded accepted answer may be pinned from dynamic wording");
        }
    }

    /** Projects one accepted resolution outcome onto the bounded, safe Tool answer. */
    public static ReferenceResolutionToolOutput of(AudienceReferenceResolution resolution) {
        if (resolution == null) {
            throw ReferenceToolException.invalid(
                    ReferenceToolErrorCodes.REFERENCE_TOOL_INCONSISTENT,
                    "A resolution answer requires the resolution outcome it projects");
        }
        return new ReferenceResolutionToolOutput(
                resolution.referenceId(),
                resolution.disposition(),
                resolution.resolved(),
                resolution.resolvedLabel(),
                resolution.dynamicWording(),
                resolution.pinnedFromDynamicWording(),
                resolution.choices(),
                resolution.questionCodes(),
                resolution.candidateCount(),
                resolution.evidence() == null ? null : resolution.evidence().evidenceHash());
    }

    /** True only for the shape that pinned one exact immutable version. */
    public boolean isResolved() {
        return disposition.isResolved();
    }

    /** A bounded diagnostic: the identity, the disposition and counts, never a label or a member. */
    @Override
    public String toString() {
        return "ReferenceResolutionToolOutput[referenceId=" + referenceId
                + ", disposition=" + disposition
                + ", candidates=" + candidateCount
                + ", choices=" + choices.size()
                + ", pinned=" + (resolved == null ? "none" : resolved.identityKey())
                + ']';
    }
}