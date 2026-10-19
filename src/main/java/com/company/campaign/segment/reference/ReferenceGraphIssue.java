package com.company.campaign.segment.reference;

/**
 * One deterministic reference-graph finding, bound to the stable node identity of the site that produced it.
 *
 * <p>It carries a stable {@link ReferenceErrorCodes} code and a bounded, non-enumerating message. The node
 * identity is the site's own {@code nodeId}, which is not sensitive; no version identifier, tenant, program,
 * DataScope or member is echoed in the message, so a rejected document never discloses what it was not
 * allowed to see.
 *
 * @param code    one {@link ReferenceErrorCodes} value
 * @param message a bounded outward message that echoes no payload identifier
 * @param nodeId  the stable identity of the reference site this finding belongs to
 * @param depth   the discovery depth the finding was observed at, counted from the document root as 0
 */
public record ReferenceGraphIssue(String code, String message, String nodeId, int depth) {

    public ReferenceGraphIssue {
        if (code == null || code.isBlank()) {
            throw ReferenceException.invalid("A reference graph issue requires a stable code");
        }
        if (message == null || message.isBlank()) {
            throw ReferenceException.invalid("A reference graph issue requires an outward message");
        }
        if (nodeId == null || nodeId.isBlank()) {
            throw ReferenceException.invalid("A reference graph issue requires its stable node identity");
        }
        if (depth < 0) {
            throw ReferenceException.invalid("A reference graph issue requires a non-negative depth");
        }
    }
}