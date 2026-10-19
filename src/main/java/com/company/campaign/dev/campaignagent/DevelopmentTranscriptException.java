package com.company.campaign.dev.campaignagent;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * A bounded refusal or bounded failure of the development conversation transcript.
 *
 * <p>It carries exactly one stable machine code and one value-free message. It never echoes a directory, a file
 * name, an identifier value, a model output, a prompt, a credential, a URL, an exception message or a stack
 * trace, so the transcript facility can never become a channel for restricted data.
 *
 * <p>The only refusal that is raised as an exception is an unsafe conversation identifier: an identifier that
 * cannot be used as a bounded, contained file name is refused before any path is built, so a traversal or a
 * symbolic-link escape is a fail-closed boundary rather than a silently skipped write. A bounded append
 * failure is reported through {@link DevelopmentTranscriptRecorder#diagnostics()} instead, and never claims a
 * transcript was written.
 */
public final class DevelopmentTranscriptException extends ContractException {

    private static final long serialVersionUID = 1L;

    public DevelopmentTranscriptException(String code, String message) {
        super(code, message, Map.of());
    }

    public DevelopmentTranscriptException(String code, String message, Throwable cause) {
        super(code, message, Map.of(), cause);
    }

    /** The bounded refusal of an identifier that could not become a contained file name. */
    static DevelopmentTranscriptException unsafeConversationIdentifier() {
        return new DevelopmentTranscriptException(
                DevelopmentCampaignAgentErrorCodes.TRANSCRIPT_PATH_UNSAFE,
                "The conversation identifier cannot be used as a bounded, contained transcript file name.");
    }
}