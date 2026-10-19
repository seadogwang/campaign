package com.company.campaign.segment.intent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A first-class Existing Audience Reference the utterance asks for, before anything about it is trusted.
 *
 * <p>It carries exactly three things: its own stable condition identity, the bounded natural-language
 * <em>candidate</em> texts the user used to name the audience, and the one {@link IntentProvenance} form the
 * request was derived from. A candidate text is a hypothesis — a name, an alias or a caller-visible identifier
 * typed by a model — and never a version pin: there is deliberately no {@code audienceId}, {@code versionId},
 * {@code versionHash}, {@code current}, {@code latest}, owner, program, DataScope, permission, dependency or
 * Gate field, so a model cannot declare any trusted fact about the audience it wants to reference. The strict
 * reader refuses every one of those names as reserved rather than silently dropping it.
 *
 * <p>The exact immutable version is decided later, by host resolution against the host-owned directory, and is
 * recorded in resolution evidence rather than here. Dynamic wording such as "当前人群" is a property of the
 * request text, not of this record: the resolver re-derives it from the grounded source span itself, so no
 * model-supplied flag can switch the check off.
 *
 * <p>The {@code referenceId} is the stable condition identity the logical expression addresses, exactly like a
 * clause id, and it is the identity every later deterministic artifact keys on.
 */
public record IntentAudienceReference(
        String referenceId,
        List<String> candidateTexts,
        IntentProvenance provenance
) {

    private static final Pattern REFERENCE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

    public IntentAudienceReference {
        if (referenceId == null || !REFERENCE_ID.matcher(referenceId).matches()) {
            throw IntentException.invalid(
                    IntentErrorCodes.AUDIENCE_REFERENCE_INVALID,
                    "An audience reference requires a bounded stable identity");
        }
        if (referenceId.length() > IntentLimits.MAX_CLAUSE_ID_LENGTH) {
            throw new IntentException(
                    IntentErrorCodes.AUDIENCE_REFERENCE_INVALID,
                    "An audience reference identity exceeds the length limit");
        }
        if (provenance == null) {
            throw IntentException.invalid(
                    IntentErrorCodes.PROVENANCE_INVALID,
                    "Every audience reference requires exactly one provenance form");
        }
        candidateTexts = boundedCandidates(candidateTexts);
    }

    /**
     * The bounded, duplicate-free candidate texts, in declaration order.
     *
     * <p>An audience reference must name at least one candidate: a request that names nothing is not a
     * reference at all, and resolution could only guess. Text is bounded and must contain no control characters
     * or lone surrogate, and a repeated candidate is refused rather than de-duplicated, so a model cannot pad a
     * candidate set into looking more decisive than it is.
     */
    private static List<String> boundedCandidates(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw IntentException.invalid(
                    IntentErrorCodes.AUDIENCE_REFERENCE_INVALID,
                    "An audience reference must name at least one candidate");
        }
        if (candidates.size() > IntentLimits.MAX_LIST_ENTRIES) {
            throw new IntentException(
                    IntentErrorCodes.LIMIT_EXCEEDED, "An audience reference exceeds its candidate limit");
        }
        Set<String> unique = new LinkedHashSet<>();
        List<String> copy = new ArrayList<>(candidates.size());
        for (String candidate : candidates) {
            String text = IntentTexts.requireText(candidate, "audienceReference.candidateTexts");
            if (text.isBlank()) {
                throw IntentException.invalid(
                        IntentErrorCodes.AUDIENCE_REFERENCE_INVALID,
                        "An audience reference candidate must carry visible text");
            }
            if (!unique.add(text)) {
                throw IntentException.invalid(
                        IntentErrorCodes.AUDIENCE_REFERENCE_INVALID,
                        "An audience reference repeats one candidate text");
            }
            copy.add(text);
        }
        return List.copyOf(copy);
    }

    /** A bounded diagnostic: the identity and a count, never the candidate text or a span. */
    @Override
    public String toString() {
        return "IntentAudienceReference[referenceId=" + referenceId
                + ", candidates=" + candidateTexts.size()
                + ']';
    }
}