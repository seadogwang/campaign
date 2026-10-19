package com.company.campaign.builder.campaignagent;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The closed outcome of one host-owned Segment source resolution.
 *
 * <p>There are exactly two shapes. {@link Source} carries the accepted resolved-or-terminal Segment submission
 * of one utterance. {@link Unavailable} carries one bounded machine code for a source this build could not
 * resolve honestly — for example because its observed artifacts were incomplete, because its Preview or model
 * boundary failed, or because no accepted Segment source is wired at all.
 *
 * <p>There is deliberately no third shape: a source can never be partially resolved, and an unavailable source
 * can never carry a fabricated artifact, request, proof or Preview. The union is what makes "the Segment Skill
 * could not run" a bounded, honest outcome instead of a null that a caller might mistake for a resolved source.
 */
public sealed interface CampaignSegmentSubmission {

    /** The accepted resolved-or-terminal Segment submission of one utterance. */
    record Source(CampaignSegmentSource source) implements CampaignSegmentSubmission {

        public Source {
            Objects.requireNonNull(source, "source");
        }
    }

    /** One bounded machine code for a Segment source this build cannot resolve honestly. */
    record Unavailable(String code) implements CampaignSegmentSubmission {

        private static final Pattern MACHINE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

        public Unavailable {
            if (code == null || !MACHINE_CODE.matcher(code).matches()) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.SKILL_UNAVAILABLE, "segmentUnavailableCode",
                        "An unavailable Segment source requires one bounded machine code.");
            }
        }
    }

    /** True when this submission carries an accepted source. */
    default boolean hasSource() {
        return this instanceof Source;
    }

    /** One submission of an accepted resolved-or-terminal source. */
    static CampaignSegmentSubmission of(CampaignSegmentSource source) {
        return new Source(source);
    }

    /** One bounded unavailable submission. */
    static CampaignSegmentSubmission unavailable(String code) {
        return new Unavailable(code);
    }
}