package com.company.campaign.segment.reference;

import com.company.campaign.contracts.ContractPatterns;

import java.util.regex.Pattern;

/**
 * One bounded, non-disclosing summary of an audience a caller may reference.
 *
 * <p>It is the <em>only</em> thing audience discovery can answer with: the stable audience identity and one
 * already-safe presentation label. There is deliberately no owner, no program, no DataScope, no permission, no
 * version pin, no publication state and no count of what the caller cannot see, so a directory that returns a
 * summary has disclosed nothing beyond the fact that this caller may reference this identity.
 *
 * <p>An audience that is not referenceable is simply absent from the result rather than present-and-refused:
 * "you cannot use this" and "this does not exist" must be indistinguishable at this boundary, so a discovery
 * result can never be used to enumerate foreign tenants or forbidden programs.
 */
public record AudienceSummary(String audienceId, String displayLabel) {

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public AudienceSummary {
        if (audienceId == null || !REFERENCE_ID.matcher(audienceId).matches()) {
            throw ReferenceException.invalid("An audience summary requires a bounded audience identity");
        }
        displayLabel = ReferenceLabels.requireLabel(displayLabel, "An audience summary requires a safe label");
    }

    /** A bounded diagnostic: the identity and the label length, never the label text. */
    @Override
    public String toString() {
        return "AudienceSummary[audienceId=" + audienceId
                + ", labelLength=" + displayLabel.length()
                + ']';
    }
}