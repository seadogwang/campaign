package com.company.campaign.segment.resolution;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.segment.reference.AudienceVersionRef;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The deterministic outcome of resolving one Existing Audience Reference against the host-owned directory.
 *
 * <p>Exactly one of five shapes is representable, and the constructor binds the shape to the disposition so a
 * contradictory outcome cannot be built:
 *
 * <ul>
 *   <li><strong>resolved</strong> — one eligible immutable published version was found and its exact
 *       version identity and content hash are pinned, together with the audience it belongs to, that version's
 *       publication instant and the complete resolution evidence. Nothing else may carry a pin or evidence;</li>
 *   <li><strong>needs input</strong> — several audiences or several eligible versions match, and exactly the
 *       safe choices that must be answered are offered. No pin is chosen, because choosing one would be the
 *       guess the contract forbids;</li>
 *   <li><strong>unsupported</strong> — no candidate audience is visible or none has an eligible published
 *       version, which is the one non-enumerating "unavailable" result;</li>
 *   <li><strong>denied</strong> — a candidate that discovery already surfaced may no longer be referenced.
 *       It carries no candidate list and no existence statement, so it discloses nothing beyond what the caller
 *       could already see.</li>
 * </ul>
 *
 * <p>The distinction between the last two is deliberate and is the whole non-enumeration story: an audience
 * the caller cannot see is indistinguishable from one that does not exist, because discovery never surfaces
 * it, while an audience the caller already saw and cannot use is reported as denied rather than pretending it
 * vanished.
 *
 * <p>Dynamic wording such as "当前人群" is a property of the request and never of the outcome: it is recorded
 * here, and when the resolver nevertheless pinned exactly one immutable version it also records that the pin
 * had to be disclosed explicitly — which is what "explicitly expose that pin in the review result" means.
 *
 * <p>{@code questionCodes} carries the stable question of a needs-input outcome and, for an unavailable one,
 * the single bounded reason code; only an accepted outcome carries neither choices nor codes, because it has
 * nothing left to ask.
 */
public record AudienceReferenceResolution(
        String referenceId,
        Disposition disposition,
        AudienceVersionRef resolved,
        String resolvedLabel,
        Instant publishedAt,
        boolean dynamicWording,
        boolean pinnedFromDynamicWording,
        List<AudienceReferenceChoice> choices,
        List<QuestionCode> questionCodes,
        int candidateCount,
        AudienceReferenceEvidence evidence
) {

    public AudienceReferenceResolution {
        if (referenceId == null || disposition == null) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "An audience reference resolution requires its identity and disposition");
        }
        if (candidateCount < 0) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "An audience reference resolution candidate count must not be negative");
        }
        choices = choices == null ? List.of() : List.copyOf(new ArrayList<>(choices));
        questionCodes = questionCodes == null ? List.of() : List.copyOf(new ArrayList<>(questionCodes));
        if (choices.size() > ResolutionLimits.MAX_CLARIFICATION_OPTIONS) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "An audience reference resolution exceeds the choice limit");
        }
        boolean accepted = disposition.isResolved();
        if (accepted != (resolved != null)
                || accepted != (resolvedLabel != null)
                || accepted != (publishedAt != null)
                || accepted != (evidence != null)) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "Only an accepted audience reference resolution may name a pin, a label and its evidence");
        }
        if (accepted && (!choices.isEmpty() || !questionCodes.isEmpty())) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "An accepted audience reference resolution must not offer a choice");
        }
        if (disposition == Disposition.NEEDS_INPUT && questionCodes.isEmpty()) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "A needs-input audience reference resolution requires its question codes");
        }
        if (pinnedFromDynamicWording && (!accepted || !dynamicWording)) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "Only a dynamic-worded accepted resolution may be pinned from dynamic wording");
        }
    }

    /** True only for the shape that pinned one exact immutable version. */
    public boolean isResolved() {
        return disposition.isResolved();
    }

    /** True when this outcome must be answered by the caller before a reference exists. */
    public boolean needsInput() {
        return disposition == Disposition.NEEDS_INPUT;
    }

    /** The single question code of a needs-input outcome, or null for every other shape. */
    public QuestionCode questionCode() {
        return questionCodes.isEmpty() ? null : questionCodes.get(0);
    }

    /** A bounded diagnostic: the identity, the disposition, counts and booleans, never a label or a member. */
    @Override
    public String toString() {
        return "AudienceReferenceResolution[referenceId=" + referenceId
                + ", disposition=" + disposition
                + ", candidates=" + candidateCount
                + ", choices=" + choices.size()
                + ", dynamicWording=" + dynamicWording
                + ", pinned=" + (resolved == null ? "none" : resolved.identityKey())
                + ']';
    }
}