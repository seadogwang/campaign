package com.company.campaign.builder.compiler;

import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.proposal.MoveAnchorKind;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.builder.testsupport.FixtureCapability;
import com.company.campaign.builder.testsupport.RecordingAuthorizationService;
import com.company.campaign.shared.error.ContractException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static com.company.campaign.builder.testsupport.BuilderFixtures.FLOW;
import static com.company.campaign.builder.testsupport.BuilderFixtures.SEGMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Requirements 7 and 8: relocation is always a MOVE against one stable relative anchor, identity survives a
 * move, contradictory anchors fail closed, and the produced move set is minimal, deterministic and exactly
 * replayable.
 */
class CampaignCompilerMoveTest {

    private static final String SEGMENT_VERSION = "segment-rule-based-1.0";
    private static final String FLOW_VERSION = "flow-message-1.0";

    private final RecordingAuthorizationService authorization = new RecordingAuthorizationService();
    private final CampaignCompiler compiler = new CampaignCompiler(authorization);
    private final FrozenAuthoringPolicy policy = BuilderFixtures.policy();
    private final FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
            FixtureCapability.passThrough(SEGMENT, SEGMENT_VERSION, FixtureCapability.ordered("segment")),
            FixtureCapability.passThrough(FLOW, FLOW_VERSION, FixtureCapability.ordered("flow")));

    // ------------------------------------------------------------------ requirement 7

    @Test
    void aMoveRelocatesAnObjectWithoutChangingItsIdentityOrContent() {
        List<ResolvedComponent> current = orderedComponents("a", "b", "c");
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .move("c", MoveAnchorKind.BEFORE, "a")
                .build();
        CampaignPlan plan = compile(patch, current);

        assertThat(ids(plan)).containsExactly("c", "a", "b");
        assertThat(plan.manifest().component("c")).isEqualTo(current.get(2));
        assertThat(plan.semanticDiff().count(ChangeKind.MOVE)).isEqualTo(1);
        assertThat(plan.semanticDiff().count(ChangeKind.DELETE)).isZero();
        assertThat(plan.semanticDiff().count(ChangeKind.ADD)).isZero();
        assertThat(plan.semanticDiff().count(ChangeKind.UPDATE)).isZero();
        assertThat(plan.changeSet().nodes()).singleElement().isInstanceOf(ChangeSetNode.Move.class);
    }

    @Test
    void aMoveMayUseAnAfterAnchor() {
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .move("a", MoveAnchorKind.AFTER, "c")
                .build();
        CampaignPlan plan = compile(patch, orderedComponents("a", "b", "c"));
        assertThat(ids(plan)).containsExactly("b", "c", "a");
    }

    @Test
    void aMoveMayAddressANewlyCreatedObjectAndAnchorToIt() {
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of("spec", "x"))
                .moveRef("segment-new", MoveAnchorKind.BEFORE, "b")
                .build();
        CampaignPlan plan = compile(patch, orderedComponents("a", "b"));
        String created = plan.clientRefBindings().get(0).logicalId();
        assertThat(ids(plan)).containsExactly("a", created, "b");
    }

    @Test
    void aMoveThatAnchorsToAnUnknownObjectFailsClosed() {
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .move("a", MoveAnchorKind.BEFORE, "missing")
                .build();
        ContractException failure = catchThrowableOfType(
                () -> compile(patch, orderedComponents("a", "b")), ContractException.class);
        assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_ANCHOR_UNRESOLVED);
    }

    @Test
    void aMoveOfAnObjectThatDoesNotExistFailsClosed() {
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .move("missing", MoveAnchorKind.BEFORE, "a")
                .build();
        ContractException failure = catchThrowableOfType(
                () -> compile(patch, orderedComponents("a", "b")), ContractException.class);
        assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_ANCHOR_UNRESOLVED);
    }

    // ------------------------------------------------------------------ requirement 8

    @Test
    void everyPermutationProducesAMinimalMoveSetThatReplaysExactly() {
        for (int size = 1; size <= 6; size++) {
            List<String> baseline = new ArrayList<>();
            for (int index = 0; index < size; index++) {
                baseline.add("c" + index);
            }
            for (List<String> target : permutations(baseline)) {
                List<ManifestOrdering.MoveInstruction> moves = ManifestOrdering.minimumMoves(baseline, target);
                assertThat(moves)
                        .as("minimum cardinality for %s -> %s", baseline, target)
                        .hasSize(baseline.size() - longestIncreasingSubsequenceLength(baseline, target));
                assertThat(replayMoves(baseline, moves))
                        .as("replay of %s -> %s", baseline, target)
                        .isEqualTo(target);
            }
        }
    }

    @Test
    void moveOutputIsDeterministicForTheSameInput() {
        List<String> baseline = List.of("a", "b", "c", "d", "e");
        List<String> target = List.of("e", "c", "a", "d", "b");
        assertThat(ManifestOrdering.minimumMoves(baseline, target))
                .isEqualTo(ManifestOrdering.minimumMoves(baseline, target));
    }

    @Test
    void replayingTheChangeSetReproducesTheManifestExactly() {
        List<ResolvedComponent> current = orderedComponents("a", "b", "c", "d", "e");
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .move("e", MoveAnchorKind.BEFORE, "a")
                .move("b", MoveAnchorKind.AFTER, "d")
                .delete("c")
                .upsertNew(SEGMENT, "segment-new", Map.of("spec", "x"))
                .build();
        CampaignPlan plan = compile(patch, current);

        List<ResolvedComponent> replayed = replay(current, plan.changeSet());
        assertThat(replayed).isEqualTo(plan.manifest().components());
        assertThat(replayed.stream().map(ResolvedComponent::logicalId).toList()).isEqualTo(ids(plan));
    }

    @Test
    void aChangeSetNeverRepresentsAMoveAsADeleteAndAnAdd() {
        List<ResolvedComponent> current = orderedComponents("a", "b", "c");
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .move("c", MoveAnchorKind.BEFORE, "a")
                .build();
        CampaignPlan plan = compile(patch, current);

        assertThat(plan.changeSet().nodes()).allMatch(node -> node instanceof ChangeSetNode.Move);
        assertThat(plan.semanticDiff().entries())
                .allMatch(entry -> entry.kind() == ChangeKind.MOVE);
    }

    @Test
    void anUnchangedOrderProducesNoMoves() {
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsert(SEGMENT, "a", Map.of("spec", "unchanged"))
                .build();
        CampaignPlan plan = compile(patch, orderedComponents("a", "b", "c"));
        assertThat(plan.changeSet().isEmpty()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private CampaignPlan compile(CampaignProposalPatch patch, List<ResolvedComponent> current) {
        return compiler.compile(CompileRequest.edit(
                patch, BuilderFixtures.current(12L, 3L, null, current), catalog, policy,
                BuilderFixtures.instants(), BuilderFixtures.context()));
    }

    private static List<String> ids(CampaignPlan plan) {
        return plan.manifest().components().stream().map(ResolvedComponent::logicalId).toList();
    }

    private List<ResolvedComponent> orderedComponents(String... logicalIds) {
        List<ResolvedComponent> components = new ArrayList<>();
        for (String logicalId : logicalIds) {
            components.add(BuilderFixtures.component(
                    logicalId, SEGMENT, SEGMENT_VERSION, FixtureCapability.ordered("segment"),
                    Map.of("spec", "unchanged")));
        }
        return components;
    }

    /** Replays a ChangeSet onto a current component list using only its closed typed values. */
    static List<ResolvedComponent> replay(List<ResolvedComponent> current, ChangeSet changeSet) {
        List<ResolvedComponent> working = new ArrayList<>(current);
        for (ChangeSetNode node : changeSet.nodes()) {
            switch (node) {
                case ChangeSetNode.Add add -> working.add(add.component());
                case ChangeSetNode.Update update -> {
                    int index = indexOf(working, update.logicalId());
                    working.set(index, applyUpdate(working.get(index), update.changes()));
                }
                case ChangeSetNode.Move move -> {
                    ResolvedComponent mover = working.remove(indexOf(working, move.logicalId()));
                    int anchor = indexOf(working, move.anchorLogicalId());
                    working.add(move.anchorKind() == MoveAnchorKind.BEFORE ? anchor : anchor + 1, mover);
                }
                case ChangeSetNode.Delete delete -> working.remove(indexOf(working, delete.logicalId()));
            }
        }
        return List.copyOf(working);
    }

    private static ResolvedComponent applyUpdate(ResolvedComponent base, List<AssetChange> changes) {
        String capability = base.capabilityCode();
        String version = base.contractVersion();
        CompositionSlot slot = base.slot();
        CapabilityPayload value = base.value();
        List<ResolvedCatalogReference> references = base.references();
        for (AssetChange change : changes) {
            switch (change) {
                case AssetChange.CapabilityChanged changed -> capability = changed.capabilityCode();
                case AssetChange.ContractVersionChanged changed -> version = changed.contractVersion();
                case AssetChange.SlotChanged changed -> slot = changed.slot();
                case AssetChange.ValueChanged changed -> value = changed.value();
                case AssetChange.ReferencesChanged changed -> references = changed.references();
            }
        }
        return new ResolvedComponent(base.logicalId(), capability, version, slot, value, references);
    }

    private static int indexOf(List<ResolvedComponent> components, String logicalId) {
        for (int index = 0; index < components.size(); index++) {
            if (components.get(index).logicalId().equals(logicalId)) {
                return index;
            }
        }
        throw new IllegalStateException("missing component " + logicalId);
    }

    /** Replays move instructions onto the baseline, the way a host would. */
    static List<String> replayMoves(List<String> baseline, List<ManifestOrdering.MoveInstruction> moves) {
        LinkedList<String> working = new LinkedList<>(baseline);
        for (ManifestOrdering.MoveInstruction move : moves) {
            working.remove(move.logicalId());
            int anchor = working.indexOf(move.anchorLogicalId());
            working.add(move.anchorKind() == MoveAnchorKind.BEFORE ? anchor : anchor + 1, move.logicalId());
        }
        return List.copyOf(working);
    }

    /** Independent O(n^2) longest-increasing-subsequence length, used as the minimality oracle. */
    static int longestIncreasingSubsequenceLength(List<String> baseline, List<String> target) {
        List<Integer> sequence = new ArrayList<>();
        for (String element : baseline) {
            sequence.add(target.indexOf(element));
        }
        int size = sequence.size();
        int[] best = new int[size];
        int longest = 0;
        for (int index = 0; index < size; index++) {
            best[index] = 1;
            for (int previous = 0; previous < index; previous++) {
                if (sequence.get(previous) < sequence.get(index)) {
                    best[index] = Math.max(best[index], best[previous] + 1);
                }
            }
            longest = Math.max(longest, best[index]);
        }
        return longest;
    }

    static List<List<String>> permutations(List<String> source) {
        List<List<String>> results = new ArrayList<>();
        permute(new ArrayList<>(source), 0, results);
        return results;
    }

    private static void permute(List<String> current, int index, List<List<String>> results) {
        if (index == current.size()) {
            results.add(List.copyOf(current));
            return;
        }
        for (int swap = index; swap < current.size(); swap++) {
            Collections.swap(current, index, swap);
            permute(current, index + 1, results);
            Collections.swap(current, index, swap);
        }
    }
}