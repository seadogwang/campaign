package com.company.campaign.segment.reference;

import com.company.campaign.metadata.domain.SubjectKind;

/**
 * One reference site in a document under validation: the node it sits on, the exact pin it declared and the
 * subject scope it is being used in.
 *
 * <p>The scope subject is supplied by the validator, which is the only component that knows whether the site
 * is the document root or the body of an {@code EXISTS}. It is never read from the DSL node, so a reference
 * can never declare the subject it wishes to be judged against.
 */
public record ReferenceCandidate(String nodeId, AudienceVersionRef reference, SubjectKind scopeSubject) {

    public ReferenceCandidate {
        if (nodeId == null || nodeId.isBlank()) {
            throw ReferenceException.invalid("A reference candidate requires the stable node identity it sits on");
        }
        if (reference == null) {
            throw ReferenceException.invalid("A reference candidate requires the exact pin it declared");
        }
        if (scopeSubject == null) {
            throw ReferenceException.invalid("A reference candidate requires the subject scope it is used in");
        }
    }
}