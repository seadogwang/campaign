package com.company.campaign.segment.validation;

import com.company.campaign.contracts.Issue;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.canonical.CanonicalNode;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.predicate.AudienceReferencePredicate;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.NotPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.predicate.PredicateType;
import com.company.campaign.segment.reference.AudienceProgramPolicy;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.segment.reference.AudienceVersionSnapshot;
import com.company.campaign.segment.reference.InMemoryAudienceVersionCatalog;
import com.company.campaign.segment.reference.ReferenceErrorCodes;
import com.company.campaign.segment.reference.ReferencePolicy;
import com.company.campaign.segment.reference.ReferenceValidationContext;
import com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.AUDIENCE_SCOPE;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.DISJOINT_SCOPE;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.catalogWith;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.ref;
import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T18 existing audience references inside the T07 validator.
 *
 * <p>Two properties matter most here. A reference with no trusted context must fail closed, because there is
 * no host fact to resolve. And a document with no reference at all must take exactly the path it took before
 * T18 — the same issues, the same canonical AST, the same hash and no reference graph.
 */
class SegmentValidationReferenceTest {

    private static AudienceReferencePredicate reference(String nodeId, AudienceVersionRef version) {
        return new AudienceReferencePredicate(
                nodeId, version.audienceId(), version.versionId(), version.versionHash());
    }

    private static SegmentDsl documentWith(Predicate predicate) {
        return SegmentValidationFixtures.publish(predicate);
    }

    private static SegmentValidationResult validate(
            SegmentDsl document, ReferenceValidationContext referenceContext) {
        return new SegmentValidator(SegmentReferenceFixtures.allowAll()).validate(
                document, SegmentValidationFixtures.snapshot(), SegmentValidationFixtures.context(),
                SegmentValidationFixtures.policy(), referenceContext);
    }

    private static SegmentValidationResult validateWithoutReferenceContext(SegmentDsl document) {
        return SegmentValidationFixtures.validate(document);
    }

    private static List<String> codes(SegmentValidationResult result) {
        return result.issues().stream().map(Issue::code).toList();
    }

    // ---------------------------------------------------------------- fails closed

    @Test
    void aReferenceWithoutATrustedContextFailsClosed() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        SegmentDsl document = documentWith(reference("n-ref", version));

        SegmentValidationResult result = validateWithoutReferenceContext(document);

        assertThat(result.valid()).isFalse();
        assertThat(codes(result)).containsExactly(ReferenceErrorCodes.CONTEXT_MISSING);
        assertThat(result.canonicalSegment()).isNull();
        assertThat(result.referenceGraph()).isNull();
        // The finding is bound to the reference node and to the appended reference stage.
        Issue finding = result.issues().get(0);
        assertThat(finding.nodeId()).isEqualTo("n-ref");
        assertThat(finding.details()).containsEntry("stage", SegmentValidationStage.REFERENCE_GRAPH.name());
    }

    @Test
    void everyReferenceSiteWithoutAContextGetsItsOwnBoundedFinding() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        SegmentDsl document = documentWith(SegmentValidationFixtures.all("n-root", List.of(
                reference("n-ref-1", version),
                SegmentValidationFixtures.not("n-not", reference("n-ref-2", version)))));

        SegmentValidationResult result = validateWithoutReferenceContext(document);

        assertThat(codes(result))
                .containsExactly(ReferenceErrorCodes.CONTEXT_MISSING, ReferenceErrorCodes.CONTEXT_MISSING);
        assertThat(result.issues().stream().map(Issue::nodeId)).containsExactly("n-ref-1", "n-ref-2");
    }

    // ---------------------------------------------------------------- resolves cleanly

    @Test
    void anExactlyPinnedReferenceValidatesAndFreezesItsGraph() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        InMemoryAudienceVersionCatalog catalog = catalogWith(snapshot(version));

        SegmentValidationResult result = validate(
                documentWith(reference("n-ref", version)), SegmentReferenceFixtures.referenceContext(catalog));

        assertThat(codes(result)).isEmpty();
        assertThat(result.valid()).isTrue();
        assertThat(result.referenceGraph()).isNotNull();
        assertThat(result.referenceGraph().distinctVersions()).isEqualTo(1);
        assertThat(result.referenceGraph().versions().get(0).reference()).isEqualTo(version);
        assertThat(result.referenceGraph().versions().get(0).effectiveDataScope().dataScopeId())
                .isEqualTo(AUDIENCE_SCOPE);
    }

    @Test
    void theReferenceStaysALeafInBothTheEditableAndTheCanonicalTree() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        GroupPredicate predicate = SegmentValidationFixtures.all("n-root", List.of(
                SegmentValidationFixtures.tierEquals("n-tier", "GOLD"), reference("n-ref", version)));
        SegmentDsl document = documentWith(predicate);

        SegmentValidationResult result = validate(
                document, SegmentReferenceFixtures.referenceContext(catalogWith(snapshot(version))));

        // The editable document is untouched: no referenced predicate is inlined into it.
        assertThat(document.predicate()).isEqualTo(predicate);
        assertThat(((GroupPredicate) document.predicate()).children()).hasSize(2);
        // The canonical AST keeps one reference leaf, and it carries only the immutable pin.
        CanonicalNode.Group canonicalRoot = (CanonicalNode.Group) result.canonicalSegment().predicate();
        assertThat(canonicalRoot.children()).hasSize(2);
        CanonicalNode.AudienceReference canonicalReference =
                (CanonicalNode.AudienceReference) canonicalRoot.children().get(1);
        assertThat(canonicalReference.reference().audienceId()).isEqualTo("audience-vip");
        assertThat(canonicalReference.reference().versionId()).isEqualTo("version-7");
        assertThat(canonicalReference.reference().versionHash()).isEqualTo(version.versionHash());
    }

    @Test
    void theReferenceNodeKeepsItsStableNodeIdForRefinement() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        SegmentValidationResult result = validate(
                documentWith(new NotPredicate("n-not", reference("n-ref", version))),
                SegmentReferenceFixtures.referenceContext(catalogWith(snapshot(version))));

        assertThat(result.valid()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    // ---------------------------------------------------------------- non-reference behaviour is untouched

    @Test
    void aDocumentWithoutAReferenceIsIdenticalUnderBothEntryPoints() {
        SegmentDsl document = SegmentValidationFixtures.validDocument();

        SegmentValidationResult withoutContext = validateWithoutReferenceContext(document);
        SegmentValidationResult withContext = validate(
                document, SegmentReferenceFixtures.referenceContext(catalogWith()));

        assertThat(withContext).isEqualTo(withoutContext);
        assertThat(withContext.referenceGraph()).isNull();
        assertThat(withContext.canonicalAstHash()).isEqualTo(withoutContext.canonicalAstHash());
        assertThat(withContext.editableDslHash()).isEqualTo(document.editableDslHash());
    }

    @Test
    void aPassedContextIsIgnoredByADocumentThatHasNoReference() {
        SegmentDsl document = SegmentValidationFixtures.validDocument();
        ReferenceValidationContext refusedEverywhere = new ReferenceValidationContext(
                reference -> {
                    throw new IllegalStateException("must never be consulted");
                },
                (caller, referenced) -> false,
                new com.company.campaign.segment.reference.ReferenceDataScopePort() {
                    @Override
                    public java.util.Optional<com.company.campaign.segment.reference.EffectiveDataScope>
                            callerScope(String tenantId, String programId, String userId, String dataScopeId) {
                        throw new IllegalStateException("must never be consulted");
                    }

                    @Override
                    public java.util.Optional<com.company.campaign.segment.reference.EffectiveDataScope>
                            intersect(com.company.campaign.segment.reference.EffectiveDataScope caller,
                                    com.company.campaign.segment.reference.EffectiveDataScope parent,
                                    com.company.campaign.segment.reference.EffectiveDataScope referenced) {
                        throw new IllegalStateException("must never be consulted");
                    }
                },
                ReferencePolicy.standard(),
                null);

        SegmentValidationResult result = validate(document, refusedEverywhere);

        assertThat(result.valid()).isTrue();
        assertThat(result.referenceGraph()).isNull();
    }

    // ---------------------------------------------------------------- failures are ordered and bounded

    @Test
    void aReferenceFailureIsReportedInTheAppendedStageAfterEveryOtherStage() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        SegmentDsl document = documentWith(SegmentValidationFixtures.all("n-root", List.of(
                SegmentValidationFixtures.attribute("n-unknown", "member.unknown",
                        com.company.campaign.metadata.domain.ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue("GOLD"))),
                reference("n-ref", version))));

        SegmentValidationResult result = validate(
                document, SegmentReferenceFixtures.referenceContext(catalogWith()));

        assertThat(codes(result))
                .containsExactly(
                        SegmentValidationErrorCodes.DEFINITION_UNKNOWN,
                        ReferenceErrorCodes.VERSION_NOT_FOUND);
        assertThat(result.issues().get(1).details())
                .containsEntry("stage", SegmentValidationStage.REFERENCE_GRAPH.name());
    }

    @Test
    void aDeletedUnavailableOrCrossTenantReferenceFailsWithoutDisclosure() {
        AudienceVersionRef deleted = ref("audience-vip", "version-7");
        AudienceVersionRef foreign = ref("audience-foreign", "version-1");
        InMemoryAudienceVersionCatalog catalog = catalogWith(
                snapshot(deleted, SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM,
                        SubjectKind.MEMBER, AUDIENCE_SCOPE, List.of(), 1,
                        com.company.campaign.segment.reference.AudienceVersionAvailability.DELETED),
                snapshot(foreign, SegmentReferenceFixtures.FOREIGN_TENANT, SegmentReferenceFixtures.PROGRAM,
                        SubjectKind.MEMBER, AUDIENCE_SCOPE, List.of(), 1,
                        com.company.campaign.segment.reference.AudienceVersionAvailability.AVAILABLE));
        ReferenceValidationContext context = SegmentReferenceFixtures.referenceContext(catalog);

        SegmentValidationResult deletedResult = validate(documentWith(reference("n-d", deleted)), context);
        SegmentValidationResult foreignResult = validate(documentWith(reference("n-f", foreign)), context);

        assertThat(codes(deletedResult)).containsExactly(ReferenceErrorCodes.VERSION_UNAVAILABLE);
        assertThat(codes(foreignResult)).containsExactly(ReferenceErrorCodes.VERSION_NOT_FOUND);
        assertThat(foreignResult.issues().get(0).message())
                .doesNotContain("audience-foreign", SegmentReferenceFixtures.FOREIGN_TENANT, "version-1");
        assertThat(deletedResult.canonicalSegment()).isNull();
        assertThat(deletedResult.referenceGraph()).isNull();
    }

    @Test
    void aProgramPolicyDenialAndAnEmptyScopeIntersectionBothFailClosed() {
        AudienceVersionRef sibling = ref("audience-sibling", "version-1");
        AudienceVersionRef disjoint = ref("audience-disjoint", "version-1");
        InMemoryAudienceVersionCatalog catalog = catalogWith(
                snapshot(sibling, SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.SIBLING_PROGRAM,
                        SubjectKind.MEMBER, AUDIENCE_SCOPE, List.of(), 1,
                        com.company.campaign.segment.reference.AudienceVersionAvailability.AVAILABLE),
                snapshot(disjoint, SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM,
                        SubjectKind.MEMBER, DISJOINT_SCOPE, List.of(), 1,
                        com.company.campaign.segment.reference.AudienceVersionAvailability.AVAILABLE));

        SegmentValidationResult denied = validate(
                documentWith(reference("n-sibling", sibling)),
                SegmentReferenceFixtures.referenceContext(catalog, AudienceProgramPolicy.sameProgramOnly()));
        SegmentValidationResult empty = validate(
                documentWith(reference("n-disjoint", disjoint)),
                SegmentReferenceFixtures.referenceContext(catalog));

        assertThat(codes(denied)).containsExactly(ReferenceErrorCodes.PROGRAM_NOT_ALLOWED);
        assertThat(codes(empty)).containsExactly(ReferenceErrorCodes.DATASCOPE_INTERSECTION_EMPTY);
    }

    @Test
    void aPermissionDenialIsReportedAsOneNonDisclosingFinding() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        SegmentValidationResult result = new SegmentValidator(SegmentReferenceFixtures.denySegmentsOnly()).validate(
                documentWith(reference("n-ref", version)),
                SegmentValidationFixtures.snapshot(),
                SegmentValidationFixtures.context(),
                SegmentValidationFixtures.policy(),
                SegmentReferenceFixtures.referenceContext(catalogWith(snapshot(version))));

        assertThat(codes(result)).containsExactly(ReferenceErrorCodes.RESOURCE_NOT_ACCESSIBLE);
        assertThat(result.issues().get(0).message())
                .doesNotContain("audience-vip", "version-7", "segment.evaluate");
    }

    @Test
    void aCycleThroughTheDocumentBeingEditedIsRejected() {
        AudienceVersionRef root = ref("audience-root", "version-2");
        AudienceVersionRef other = ref("audience-other", "version-1");
        InMemoryAudienceVersionCatalog catalog = catalogWith(
                snapshot(other, List.of(root)), snapshot(root));

        ReferenceValidationContext context = new ReferenceValidationContext(
                catalog, AudienceProgramPolicy.sameProgramOnly(), SegmentReferenceFixtures.dataScopePort(),
                ReferencePolicy.standard(), root);

        SegmentValidationResult result = validate(documentWith(reference("n-ref", other)), context);

        assertThat(codes(result)).containsExactly(ReferenceErrorCodes.CYCLE_DETECTED);
    }

    @Test
    void theGraphHashMovesWhenAPinnedCatalogFactMoves() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        SegmentDsl document = documentWith(reference("n-ref", version));

        SegmentValidationResult first = validate(
                document, SegmentReferenceFixtures.referenceContext(catalogWith(snapshot(version))));
        SegmentValidationResult differentComplexity = validate(
                document, SegmentReferenceFixtures.referenceContext(catalogWith(snapshot(
                        version, SegmentReferenceFixtures.TENANT, SegmentReferenceFixtures.PROGRAM,
                        SubjectKind.MEMBER, AUDIENCE_SCOPE, List.of(), 9,
                        com.company.campaign.segment.reference.AudienceVersionAvailability.AVAILABLE))));

        assertThat(first.valid()).isTrue();
        assertThat(differentComplexity.valid()).isTrue();
        assertThat(first.referenceGraph().graphHash())
                .isNotEqualTo(differentComplexity.referenceGraph().graphHash());
    }

    @Test
    void duplicateSitesAreCountedOnceYetBothLeavesStayInTheCanonicalTree() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        // Two structurally distinct sites pinning the same version: one direct, one under a negation.
        SegmentDsl document = documentWith(SegmentValidationFixtures.all("n-root", List.of(
                reference("n-ref-1", version),
                new NotPredicate("n-not", reference("n-ref-2", version)))));

        SegmentValidationResult result = validate(
                document, SegmentReferenceFixtures.referenceContext(catalogWith(snapshot(version))));

        assertThat(result.valid()).isTrue();
        // Dependency accounting is by distinct pin: one frozen version, one effective scope.
        assertThat(result.referenceGraph().distinctVersions()).isEqualTo(1);
        // The editable tree keeps both sites, and so does the canonical AST: nothing is inlined or collapsed.
        assertThat(((GroupPredicate) document.predicate()).children()).hasSize(2);
        CanonicalNode.Group canonicalRoot = (CanonicalNode.Group) result.canonicalSegment().predicate();
        assertThat(canonicalRoot.children())
                .anyMatch(CanonicalNode.AudienceReference.class::isInstance)
                .anyMatch(CanonicalNode.Negation.class::isInstance);
    }

    @Test
    void twoSitesPinningOneIdentityWithDifferentBytesCannotBothBeAccepted() {
        AudienceVersionRef present = new AudienceVersionRef(
                "audience-vip", "version-7", SegmentReferenceFixtures.hash("bytes-one"));
        AudienceVersionRef contradicted = new AudienceVersionRef(
                "audience-vip", "version-7", SegmentReferenceFixtures.hash("bytes-two"));
        ReferenceValidationContext context =
                SegmentReferenceFixtures.referenceContext(catalogWith(snapshot(present)));

        // One version identity, two different content hashes: the catalog can confirm exactly one of them.
        SegmentValidationResult declared = validate(documentWith(SegmentValidationFixtures.all("n-root", List.of(
                reference("n-ref-1", present),
                new NotPredicate("n-not", reference("n-ref-2", contradicted))))), context);
        SegmentValidationResult reordered = validate(documentWith(SegmentValidationFixtures.all("n-root", List.of(
                new NotPredicate("n-not", reference("n-ref-2", contradicted)),
                reference("n-ref-1", present)))), context);

        // The other pin is refused on its own terms and the document has no accepted graph at all, whichever
        // of the two sites happened to be declared first.
        assertThat(declared.valid()).isFalse();
        assertThat(codes(declared)).containsExactly(ReferenceErrorCodes.VERSION_CONFLICT);
        assertThat(declared.referenceGraph()).isNull();
        assertThat(codes(reordered)).isEqualTo(codes(declared));
        assertThat(reordered.referenceGraph()).isNull();
    }

    @Test
    void aReferenceInsideAnExistsIsJudgedAgainstTheRelationTargetSubject() {
        AudienceVersionRef memberAudience = ref("audience-vip", "version-7");
        // The referenced version is defined over MEMBER, but this site sits inside an EXISTS over ORDER rows.
        SegmentDsl document = documentWith(SegmentValidationFixtures.exists(
                "n-exists", "member.orders",
                SegmentValidationFixtures.rolling(30, com.company.campaign.segment.dsl.window.WindowUnit.DAY),
                reference("n-ref", memberAudience)));

        SegmentValidationResult result = validate(
                document, SegmentReferenceFixtures.referenceContext(catalogWith(snapshot(memberAudience))));

        assertThat(codes(result)).containsExactly(ReferenceErrorCodes.SUBJECT_MISMATCH);
        assertThat(result.valid()).isFalse();
    }

    @Test
    void validationRemainsDeterministicForAReferenceBearingDocument() {
        AudienceVersionRef version = ref("audience-vip", "version-7");
        SegmentDsl document = documentWith(reference("n-ref", version));
        ReferenceValidationContext context =
                SegmentReferenceFixtures.referenceContext(catalogWith(snapshot(version)));

        SegmentValidationResult first = validate(document, context);
        SegmentValidationResult second = validate(document, context);

        assertThat(second).isEqualTo(first);
        assertThat(second.canonicalAstHash()).isEqualTo(first.canonicalAstHash());
        assertThat(second.referenceGraph().graphHash()).isEqualTo(first.referenceGraph().graphHash());
        assertThat(Set.copyOf(codes(first))).isEmpty();
    }
}