package com.company.campaign.evaluation.segment.reference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One declared reference site of a reference-bearing document: its stable node identity, its exact pin and
 * the subject scope it is evaluated in.
 *
 * <p>The site is a <em>declaration</em>, not an input: the runner derives the sites from the parsed document
 * itself and requires this list to equal what it found. That is what makes the corpus honest about what its
 * documents contain — a case whose notes claim a duplicate reference it does not actually carry fails to load
 * rather than passing on a name.
 *
 * <p>The scope subject is written here because it is a property of the tree shape (the document root subject,
 * or a relation's target entity beneath an {@code EXISTS}) and naming it in the corpus is what lets a reader
 * check the expectation without re-deriving it by hand.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReferenceSiteSpec(String nodeId, ReferencePin pin, SubjectKind scopeSubject) {

    public ReferenceSiteSpec {
        if (nodeId == null || nodeId.isBlank()) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED,
                    "A declared reference site requires its stable node identity");
        }
        if (pin == null) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.PIN_INVALID,
                    "A declared reference site requires the exact pin it names");
        }
        if (scopeSubject == null) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED,
                    "A declared reference site requires the subject scope it is evaluated in");
        }
    }

    /** The stable, non-sensitive key two sites share when they sit on the same node identity. */
    public String siteKey() {
        return nodeId;
    }

    /**
     * The stable pin key that includes the exact content hash.
     *
     * <p>It is the correct key for accounting, because two sites naming one version identity with different
     * bytes are two different pins rather than one duplicated reference.
     */
    public String pinKey() {
        return pin.identityKey() + "@" + pin.versionHash().value();
    }

    /** A bounded diagnostic that names no trusted fact beyond the site's own identity. */
    @Override
    public String toString() {
        return "ReferenceSiteSpec[nodeId=" + nodeId + ", pin=" + pin.identityKey()
                + ", scopeSubject=" + scopeSubject + ']';
    }
}
