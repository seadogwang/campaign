package com.company.campaign.preview;

/**
 * A bounded, non-disclosing signal a {@link PreviewExecutionBackend} may raise.
 *
 * <p>It carries only one closed {@link Kind}, so the application service can map it to a stable Preview code
 * without ever seeing a driver message, a host name, a credential, a SQL fragment, a fixture fact or a thrown
 * cause. Nothing here is copied from a request.
 */
public final class PreviewBackendException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The closed set of backend outcomes the Preview boundary understands. */
    public enum Kind {
        /** The backend cannot serve this call right now. */
        UNAVAILABLE,
        /** The backend did not answer within its bound. */
        TIMEOUT,
        /** The backend produced a result that is not a coherent, pinned execution artifact. */
        INVALID_RESULT
    }

    private final Kind kind;

    public PreviewBackendException(Kind kind) {
        super("The Preview execution backend did not return a usable result.");
        if (kind == null) {
            throw PreviewException.of(PreviewErrorCodes.BACKEND_FAILED, "backendError");
        }
        this.kind = kind;
    }

    /** The closed outcome kind. */
    public Kind kind() {
        return kind;
    }
}