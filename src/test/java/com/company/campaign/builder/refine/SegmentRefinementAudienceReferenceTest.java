package com.company.campaign.builder.refine;

import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.predicate.AudienceReferencePredicate;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.predicate.PredicateType;
import com.company.campaign.segment.intent.testsupport.IntentFixtures;
import com.company.campaign.segment.reference.AudienceReferenceDirectoryContext;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.segment.reference.testsupport.AudienceReferenceFixtures;
import com.company.campaign.segment.resolution.AudienceReferenceEvidence;
import com.company.campaign.segment.resolution.IntentResolver;
import com.company.campaign.segment.resolution.ResolutionResult;
import com.company.campaign.builder.refine.testsupport.RefinementTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T18 section 6: T12 refinement may add, remove or move an audience reference by stable node identity, and
 * changing which immutable version it names requires a newly host-resolved pin together with stale-base
 * protection — a model may propose a pin but never choose one.
 */
class SegmentRefinementAudienceReferenceTest {

    private static final String REFERENCE_NODE = "ref-1";
    private static final String OTHER_NODE = "n-tier";

    private static final SegmentRefinementEditor EDITOR = new SegmentRefinementEditor();

    // ------------------------------------------------------------------ add / move / remove by identity

    @Test
    void anExistingReferenceMovesAndRemovesByStableIdentityWithoutAnyNewResolution() {
        SegmentDsl base = baseDocument();

        SegmentDsl moved = EDITOR.apply(base, RefinementTestFixtures.patch(
                base, new SegmentRefinementOperation.MoveNode(
                        "op-1", REFERENCE_NODE, "n-root", OTHER_NODE)));
        assertThat(nodeIds(moved.predicate())).contains(REFERENCE_NODE);
        assertThat(referenceNode(moved.predicate())).isEqualTo(referenceNode(base.predicate()));

        SegmentDsl removed = EDITOR.apply(base, RefinementTestFixtures.patch(
                base, new SegmentRefinementOperation.RemoveNode("op-1", REFERENCE_NODE)));
        assertThat(nodeIds(removed.predicate())).doesNotContain(REFERENCE_NODE);
        assertThat(nodeIds(removed.predicate())).contains(OTHER_NODE);
    }

    @Test
    void anAddedReferenceMustBeBackedByAnAcceptedHostResolution() {
        SegmentDsl base = baseDocument();
        AudienceVersionRef pin = AudienceReferenceFixtures.pin(
                AudienceReferenceFixtures.AUDIENCE_VIP, AudienceReferenceFixtures.VERSION_SEVEN);
        SegmentRefinementPatch patch = RefinementTestFixtures.patch(base,
                addReference("op-1", "ref-2", pin));

        assertThatThrownBy(() -> EDITOR.apply(base, patch, List.of()))
                .isInstanceOf(SegmentRefinementException.class)
                .extracting(thrown -> ((SegmentRefinementException) thrown).code())
                .isEqualTo(SegmentRefinementErrorCodes.REFERENCE_PIN_UNRESOLVED);

        SegmentDsl refined = EDITOR.apply(base, patch, List.of(evidence("ref-2", pin)));
        assertThat(nodeIds(refined.predicate())).contains("ref-2");
    }

    // ------------------------------------------------------------------ re-pinning

    @Test
    void repinningRefusesAReferenceThatNoLongerCarriesTheExpectedPin() {
        SegmentDsl base = baseDocument();
        AudienceVersionRef stale = AudienceReferenceFixtures.pin(
                AudienceReferenceFixtures.AUDIENCE_VIP, AudienceReferenceFixtures.VERSION_SEVEN);

        assertThatThrownBy(() -> EDITOR.apply(base, RefinementTestFixtures.patch(
                base, repin("op-1", stale, stale))))
                .isInstanceOf(SegmentRefinementException.class)
                .extracting(thrown -> ((SegmentRefinementException) thrown).code())
                .isEqualTo(SegmentRefinementErrorCodes.REFERENCE_BASE_STALE);
    }

    @Test
    void repinningRequiresAHostResolvedPinBackingTheNewExactVersion() {
        SegmentDsl base = baseDocument();
        AudienceVersionRef expected = basePin();
        AudienceVersionRef resolved = resolvedPin();

        SegmentRefinementPatch patch = RefinementTestFixtures.patch(
                base, repin("op-1", expected, resolved));

        // A model-proposed pin is not a decision: without host evidence the edit is refused.
        assertThatThrownBy(() -> EDITOR.apply(base, patch, List.of()))
                .isInstanceOf(SegmentRefinementException.class)
                .extracting(thrown -> ((SegmentRefinementException) thrown).code())
                .isEqualTo(SegmentRefinementErrorCodes.REFERENCE_PIN_UNRESOLVED);

        SegmentDsl refined = EDITOR.apply(base, patch, List.of(evidence(REFERENCE_NODE, resolved)));
        AudienceReferencePredicate repinned = referenceNode(refined.predicate());
        // The stable identity survives, and only the exact immutable pin changed.
        assertThat(repinned.nodeId()).isEqualTo(REFERENCE_NODE);
        assertThat(repinned.audienceId()).isEqualTo(resolved.audienceId());
        assertThat(repinned.versionId()).isEqualTo(resolved.versionId());
        assertThat(repinned.versionHash()).isEqualTo(resolved.versionHash());
        assertThat(nodeIds(refined.predicate())).contains(OTHER_NODE);
    }

    @Test
    void evidenceOfAnotherIdentityOrAnotherVersionDoesNotJustifyTheReference() {
        SegmentDsl base = baseDocument();
        AudienceVersionRef expected = basePin();
        AudienceVersionRef resolved = resolvedPin();
        SegmentRefinementPatch patch = RefinementTestFixtures.patch(
                base, repin("op-1", expected, resolved));

        // The right pin attributed to the wrong reference identity is not evidence for this node.
        assertThatThrownBy(() -> EDITOR.apply(base, patch, List.of(evidence("ref-2", resolved))))
                .isInstanceOf(SegmentRefinementException.class)
                .extracting(thrown -> ((SegmentRefinementException) thrown).code())
                .isEqualTo(SegmentRefinementErrorCodes.REFERENCE_PIN_UNRESOLVED);

        // The same reference identity resolved by the host to a different immutable version — different
        // version identity and therefore different exact content hash — is a different pin, and it does not
        // justify the version this edit pins.
        AudienceReferenceEvidence older = resolve(
                REFERENCE_NODE, AudienceReferenceFixtures.earlierVersionDirectory().context())
                .audienceReference(REFERENCE_NODE).evidence();
        assertThat(older.resolved().audienceId()).isEqualTo(resolved.audienceId());
        assertThat(older.resolved()).isNotEqualTo(resolved);
        assertThatThrownBy(() -> EDITOR.apply(base, patch, List.of(older)))
                .isInstanceOf(SegmentRefinementException.class)
                .extracting(thrown -> ((SegmentRefinementException) thrown).code())
                .isEqualTo(SegmentRefinementErrorCodes.REFERENCE_PIN_UNRESOLVED);
    }

    @Test
    void aReferenceFreeDocumentIsUnaffectedByTheReferenceProof() {
        SegmentDsl referenceFree = RefinementTestFixtures.baseDocument();
        SegmentRefinementPatch patch = RefinementTestFixtures.patch(
                referenceFree, RefinementTestFixtures.replaceWindow("op-1", "n-spend", 30));

        // No evidence is required, and the result is exactly the accepted reference-free refinement.
        assertThat(EDITOR.apply(referenceFree, patch, List.of()))
                .isEqualTo(EDITOR.apply(referenceFree, patch));
    }

    // ------------------------------------------------------------------ strict wire contract

    @Test
    void theReferencePinReplacementRoundTripsThroughTheStrictCodec() {
        SegmentDsl base = baseDocument();
        AudienceVersionRef expected = basePin();
        AudienceVersionRef resolved = resolvedPin();
        SegmentRefinementOperation operation = repin("op-1", expected, resolved);
        SegmentRefinementPatch patch = RefinementTestFixtures.patch(base, operation);

        SegmentRefinementPatch parsed = RefinementTestFixtures.parse(
                RefinementTestFixtures.write(patch));
        assertThat(parsed).isEqualTo(patch);
        assertThat(parsed.operations().get(0)).isEqualTo(operation);
    }

    @Test
    void theReferencePinShapeIsClosedAgainstUnknownAndForgedMembers() {
        SegmentDsl base = baseDocument();
        String pin = "\"audienceId\":\"" + AudienceReferenceFixtures.AUDIENCE_VIP + "\","
                + "\"versionId\":\"" + AudienceReferenceFixtures.VERSION_SIX + "\","
                + "\"versionHash\":\"" + basePin().versionHash().value() + "\"";

        // An extra member inside a pin is not a reference pin.
        assertThatThrownBy(() -> RefinementTestFixtures.parse(RefinementTestFixtures.patchJson(base,
                "[{\"opId\":\"op-1\",\"op\":\"REPLACE_FIELD\",\"nodeId\":\"ref-1\","
                        + "\"field\":\"referencePin\",\"value\":{\"expected\":{" + pin + ",\"tenantId\":\"t\"},"
                        + "\"pinned\":{" + pin + "}}}]")))
                .isInstanceOf(SegmentRefinementException.class)
                .extracting(thrown -> ((SegmentRefinementException) thrown).code())
                .isEqualTo(SegmentRefinementErrorCodes.FORBIDDEN_FIELD);

        // A bare pin without its expected/pinned pair is not the closed replacement shape either.
        assertThatThrownBy(() -> RefinementTestFixtures.parse(RefinementTestFixtures.patchJson(base,
                "[{\"opId\":\"op-1\",\"op\":\"REPLACE_FIELD\",\"nodeId\":\"ref-1\","
                        + "\"field\":\"referencePin\",\"value\":{" + pin + "}}]")))
                .isInstanceOf(SegmentRefinementException.class)
                .extracting(thrown -> ((SegmentRefinementException) thrown).code())
                .isEqualTo(SegmentRefinementErrorCodes.UNKNOWN_FIELD);
    }

    @Test
    void aReferencePinReplacementIsRefusedOnEveryNonReferenceNodeKind() {
        SegmentDsl base = baseDocument();
        AudienceVersionRef expected = basePin();
        SegmentRefinementOperation.ReplaceField operation = repin("op-1", expected, resolvedPin());

        assertThatThrownBy(() -> EDITOR.apply(base, RefinementTestFixtures.patch(
                base, new SegmentRefinementOperation.ReplaceField(
                        "op-1", OTHER_NODE, RefinementField.REFERENCE_PIN, operation.replacement()))))
                .isInstanceOf(SegmentRefinementException.class)
                .extracting(thrown -> ((SegmentRefinementException) thrown).code())
                .isEqualTo(SegmentRefinementErrorCodes.FIELD_NODE_INCOMPATIBLE);
    }

    // ------------------------------------------------------------------ fixtures

    /** A root {@code ALL} group holding one attribute and one already-pinned audience reference. */
    private static SegmentDsl baseDocument() {
        return RefinementTestFixtures.publish(new GroupPredicate("n-root", PredicateType.ALL, List.of(
                RefinementTestFixtures.tierEqualsGold(OTHER_NODE),
                referenceNode(REFERENCE_NODE, basePin()))));
    }

    private static AudienceReferencePredicate referenceNode(String nodeId, AudienceVersionRef pin) {
        return new AudienceReferencePredicate(nodeId, pin.audienceId(), pin.versionId(), pin.versionHash());
    }

    /** The exact immutable pin the base document already carries: an older version of the same audience. */
    private static AudienceVersionRef basePin() {
        return AudienceReferenceFixtures.pin(
                AudienceReferenceFixtures.AUDIENCE_VIP, AudienceReferenceFixtures.VERSION_SIX);
    }

    /**
     * The pin the host directory actually resolves for {@code ref-1}, taken from the genuine T10 resolver so
     * the test rests on real host evidence rather than on one it manufactured.
     */
    private static AudienceVersionRef resolvedPin() {
        return resolve(REFERENCE_NODE).audienceReference(REFERENCE_NODE).resolved();
    }

    private static AudienceReferenceEvidence evidence(String referenceId, AudienceVersionRef pin) {
        AudienceReferenceEvidence produced = resolve(referenceId).audienceReference(referenceId).evidence();
        // The host only ever mints evidence for the pin it actually resolved, so an evidence for any other
        // pin cannot exist; a fixture that quietly substituted a matching one would turn a refusal proof into
        // a tautology, so the contradiction is refused here instead.
        if (!produced.resolved().equals(pin)) {
            throw new IllegalStateException(
                    "The host cannot produce evidence for a pin it did not resolve: " + pin.pinKey());
        }
        return produced;
    }

    /** Resolves one reference identity against the host-owned single-version directory. */
    private static ResolutionResult resolve(String referenceId) {
        return resolve(referenceId, AudienceReferenceFixtures.singleVersionDirectory().context());
    }

    /** Resolves one reference identity against one host-owned directory state. */
    private static ResolutionResult resolve(
            String referenceId, AudienceReferenceDirectoryContext directory) {
        IntentResolver resolver = new IntentResolver(
                com.company.campaign.builder.agent.testsupport.AgentTestFixtures.snapshot(),
                com.company.campaign.builder.segment.testsupport.SliceTestFixtures.policy(),
                com.company.campaign.builder.segment.testsupport.SliceTestFixtures.registry(),
                directory);
        return resolver.resolve(IntentFixtures.referenceIntentWithReference(
                IntentFixtures.audienceReference(referenceId, IntentFixtures.AUDIENCE_PHRASE)));
    }

    private static SegmentRefinementOperation.ReplaceField repin(
            String operationId, AudienceVersionRef expected, AudienceVersionRef pinned) {
        return new SegmentRefinementOperation.ReplaceField(
                operationId, REFERENCE_NODE, RefinementField.REFERENCE_PIN,
                new RefinementReplacement.ReferencePinReplacement(expected, pinned));
    }

    private static SegmentRefinementOperation addReference(
            String operationId, String nodeId, AudienceVersionRef pin) {
        return new SegmentRefinementOperation.AddNode(
                operationId, "n-root", null, referenceNode(nodeId, pin));
    }

    private static AudienceReferencePredicate referenceNode(Predicate tree) {
        return (AudienceReferencePredicate) find(tree, REFERENCE_NODE);
    }

    private static Predicate find(Predicate node, String nodeId) {
        if (node.nodeId().equals(nodeId)) {
            return node;
        }
        for (Predicate child : childrenOf(node)) {
            Predicate found = find(child, nodeId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static List<String> nodeIds(Predicate node) {
        List<String> ids = new java.util.ArrayList<>();
        collect(node, ids);
        return ids;
    }

    private static void collect(Predicate node, List<String> ids) {
        ids.add(node.nodeId());
        for (Predicate child : childrenOf(node)) {
            collect(child, ids);
        }
    }

    private static List<Predicate> childrenOf(Predicate node) {
        return switch (node) {
            case GroupPredicate group -> group.children();
            case com.company.campaign.segment.dsl.predicate.NotPredicate not -> List.of(not.child());
            case com.company.campaign.segment.dsl.predicate.ExistsPredicate exists -> List.of(exists.predicate());
            default -> List.of();
        };
    }
}