package com.company.campaign.action;

/**
 * The bounded, non-enumerating failure of one Provider call.
 *
 * <p>A Provider implementation throws exactly this and nothing else: the code is one of the bounded action
 * machine codes, the message is a closed constant and there is deliberately no cause, no stack trace and no
 * suppressed exception, so an unrestricted Provider exception text, token, URL or endpoint can never travel
 * out of the Provider boundary and into an audit record, a log or a persisted outcome.
 *
 * <p>The external-action layer maps every thrown instance to a bounded machine outcome. A thrown transport
 * error, timeout or crash is <em>never</em> read as a definitive failure: the remote effect may exist, so it
 * becomes an unknown outcome and requires reconciliation.
 */
public final class ActionProviderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The one closed message every instance carries; it never names the refused value. */
    public static final String MESSAGE = "The external Provider call did not produce a bounded result.";

    private final transient String code;

    public ActionProviderException(String code) {
        // No cause and no suppression: a Provider's own exception text must never be reachable from here.
        super(MESSAGE, null, false, false);
        ActionSupport.requireMachineCode(code, "code");
        this.code = code;
    }

    /** The stable bounded machine code of this failure. */
    public String code() {
        return code;
    }

    @Override
    public String toString() {
        return "ActionProviderException[code=" + code + ']';
    }
}