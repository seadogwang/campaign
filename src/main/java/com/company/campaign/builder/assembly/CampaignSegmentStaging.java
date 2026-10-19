package com.company.campaign.builder.assembly;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.segment.resolution.Clarification;

/**
 * The closed outcome of one narrow Segment → assembly adaptation.
 *
 * <p>{@link Staged} carries the one accepted {@code segment.rule-based} artifact the workspace may stage.
 * {@link NotAcceptable} carries the honest terminal a {@code NEEDS_INPUT}, {@code UNSUPPORTED}, {@code DENIED}
 * or {@code INVALID} Segment reached, preserving its status and — for {@code NEEDS_INPUT} — its genuine
 * clarification for presentation. {@link Refused} carries one bounded code for a submission that could not be
 * adapted at all. None of the three invents a payload, a Manifest or a Plan.
 */
public sealed interface CampaignSegmentStaging {

    /** The one accepted Segment artifact the assembly workspace may stage. */
    record Staged(CampaignSkillArtifact artifact) implements CampaignSegmentStaging {

        public Staged {
            if (artifact == null) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.ADAPTER_NOT_ELIGIBLE,
                        "A staged Segment adaptation requires its artifact.");
            }
        }

        /** A bounded diagnostic: the binding identity and the operation kind, never a payload value. */
        @Override
        public String toString() {
            return "CampaignSegmentStaging.Staged[skillId=" + artifact.skillId() + ']';
        }
    }

    /** A genuine Segment terminal that the workspace must not stage. */
    record NotAcceptable(Disposition disposition, String code, Clarification clarification)
            implements CampaignSegmentStaging {

        public NotAcceptable {
            if (disposition == null || disposition.isResolved() || code == null) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.ADAPTER_NOT_ELIGIBLE,
                        "A non-acceptable Segment adaptation requires one bounded non-resolved outcome.");
            }
            if (clarification != null && disposition != Disposition.NEEDS_INPUT) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.ADAPTER_NOT_ELIGIBLE,
                        "Only a NEEDS_INPUT Segment terminal may carry a clarification.");
            }
        }

        /** A bounded diagnostic: the disposition and the code, never the clarification's content. */
        @Override
        public String toString() {
            return "CampaignSegmentStaging.NotAcceptable[disposition=" + disposition
                    + ", code=" + code
                    + ", clarification=" + (clarification != null) + ']';
        }
    }

    /** One bounded refusal of a submission that could not be adapted. */
    record Refused(String code) implements CampaignSegmentStaging {

        public Refused {
            if (code == null || !code.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.ADAPTER_NOT_ELIGIBLE,
                        "A Segment refusal requires one bounded machine code.");
            }
        }
    }
}