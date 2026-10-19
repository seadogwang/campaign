package com.company.campaign.dev.campaignagent;

import com.company.campaign.dev.segment.DevelopmentStructuredModelPort;

import java.util.Objects;

/**
 * The observation decorator that records every raw Skill input-resolution response of the development host.
 *
 * <p>It is a pure pass-through: it delegates the one bounded structured call unchanged and returns exactly the
 * bytes the delegate returned, so the accepted strict Skill decoders see the identical document they saw before
 * and a Skill resolves, refuses or falls back to its clarification exactly as before.
 *
 * <p>The bound it applies is the accepted Skill resolver bound, so a response the resolver would have ignored as
 * out of contract is refused by the transcript too instead of being stored as if the boundary had accepted it.
 * It records only the response document — never the versioned prompt, the utterance, the answers or a model
 * setting.
 *
 * <p>When no conversation is being served on this thread, or when the transcript is disabled, it is inert.
 */
public final class DevelopmentTranscriptSkillTransport implements DevelopmentStructuredModelPort {

    /**
     * The accepted bound of {@code StructuredSkillInputResolvers}: a Skill input document the resolver would
     * ignore as over-size is not a response the accepted boundary accepted, so it is not stored.
     */
    static final int SKILL_INPUT_OUTPUT_BYTES = 32_768;

    private final DevelopmentStructuredModelPort delegate;
    private final DevelopmentTranscriptRecorder transcript;

    public DevelopmentTranscriptSkillTransport(
            DevelopmentStructuredModelPort delegate, DevelopmentTranscriptRecorder transcript) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.transcript = Objects.requireNonNull(transcript, "transcript");
    }

    @Override
    public byte[] invokeStructured(String prompt) {
        byte[] output = delegate.invokeStructured(prompt);
        transcript.recordModelResponse(
                DevelopmentTranscriptRecorder.SKILL_INPUT, output, SKILL_INPUT_OUTPUT_BYTES);
        return output;
    }

    /** A bounded diagnostic: the wiring facts only, never a prompt or an output. */
    @Override
    public String toString() {
        return "DevelopmentTranscriptSkillTransport[observing=true, " + delegate + ']';
    }
}