package com.company.campaign.segment.resolution;

/**
 * One safe, host-offered choice of an audience-reference clarification.
 *
 * <p>{@code value} is what the host must be handed back to apply the choice: an exact audience identity for an
 * ambiguous audience, or an exact immutable pin key for an ambiguous version. It is never a display label and
 * never free text, and it can only come from a candidate set the host-owned directory itself produced — a
 * caller cannot invent a value that was not offered, because the clarification's resolution id covers the
 * complete option set and a value outside it fails as stale.
 *
 * <p>{@code displayLabel} is presentation data only. Logic keys on {@code value}, never on the label.
 */
public record AudienceReferenceChoice(String value, String displayLabel) {

    public AudienceReferenceChoice {
        if (value == null || value.isBlank() || value.length() > ResolutionLimits.MAX_OPTION_LABEL_LENGTH) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "An audience reference choice requires a bounded safe value");
        }
        if (displayLabel == null || displayLabel.isBlank()
                || displayLabel.length() > ResolutionLimits.MAX_OPTION_LABEL_LENGTH) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INTERNAL_INCONSISTENT,
                    "An audience reference choice requires a bounded safe label");
        }
    }

    /** A bounded diagnostic: the label length, never the label or the value. */
    @Override
    public String toString() {
        return "AudienceReferenceChoice[valueLength=" + value.length()
                + ", labelLength=" + displayLabel.length() + ']';
    }
}