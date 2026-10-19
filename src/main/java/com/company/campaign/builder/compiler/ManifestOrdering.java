package com.company.campaign.builder.compiler;

import com.company.campaign.builder.proposal.MoveAnchorKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic minimum-cardinality relocation of an already-identified component collection.
 *
 * <p>Relocating ordered components is a problem of turning one permutation into another with the fewest
 * remove-and-reinsert operations. A longest-increasing subsequence of the target positions — taken over the
 * baseline order — is exactly the set of components that can stay put, so every component outside it is
 * moved once and the result is minimal.
 *
 * <p>The chosen subsequence is the one produced by the standard patience algorithm with first-position
 * replacement, applied to the sequence of target indices taken in baseline order. That rule is stated here
 * because the tie-break matters: among all maximum-length chains, this one prefers the earliest possible
 * tail, so the same two orders always produce the same move set.
 *
 * <p>Each move is expressed against a stable relative anchor — the moved component's immediate neighbour in
 * the target arrangement — never against an index, and the generated order is the ascending target index of
 * the moved components, so the sequence is deterministic and replaying it in that order reproduces the
 * target arrangement exactly.
 */
final class ManifestOrdering {

    private ManifestOrdering() {
    }

    /** One relocation: move {@code logicalId} immediately before or after {@code anchorLogicalId}. */
    record MoveInstruction(String logicalId, MoveAnchorKind anchorKind, String anchorLogicalId) {
    }

    /**
     * The minimal, deterministic move set that turns {@code baseline} into {@code target}.
     *
     * <p>Both lists must describe the same component set; otherwise the inputs are contradictory and this
     * method fails closed.
     */
    static List<MoveInstruction> minimumMoves(List<String> baseline, List<String> target) {
        int size = baseline.size();
        if (size != target.size()) {
            throw inconsistent("orderingSize");
        }
        Map<String, Integer> targetIndex = new HashMap<>();
        for (int index = 0; index < target.size(); index++) {
            if (targetIndex.put(target.get(index), index) != null) {
                throw inconsistent("orderingDuplicate");
            }
        }
        int[] sequence = new int[size];
        for (int index = 0; index < size; index++) {
            Integer position = targetIndex.get(baseline.get(index));
            if (position == null) {
                throw inconsistent("orderingMember");
            }
            sequence[index] = position;
        }
        if (size == 0) {
            return List.of();
        }

        boolean[] keep = longestIncreasingSubsequence(sequence);
        List<String> arranged = new ArrayList<>(size);
        Set<String> stationary = new HashSet<>();
        for (int index = 0; index < size; index++) {
            if (keep[index]) {
                arranged.add(baseline.get(index));
                stationary.add(baseline.get(index));
            }
        }

        List<MoveInstruction> moves = new ArrayList<>();
        for (int index = 0; index < target.size(); index++) {
            String logicalId = target.get(index);
            if (stationary.contains(logicalId)) {
                continue;
            }
            MoveAnchorKind anchorKind = index == 0 ? MoveAnchorKind.BEFORE : MoveAnchorKind.AFTER;
            String anchor = index == 0 ? arranged.get(0) : arranged.get(index - 1);
            arranged.add(index, logicalId);
            moves.add(new MoveInstruction(logicalId, anchorKind, anchor));
        }
        return List.copyOf(moves);
    }

    /**
     * Marks the members of one maximum-length strictly increasing subsequence of {@code sequence}.
     *
     * <p>Reconstruction follows the patience tails and picks, among all maximum-length chains, the one whose
     * successive tails are as small as possible; that is the documented tie-break of this build.
     */
    private static boolean[] longestIncreasingSubsequence(int[] sequence) {
        int size = sequence.length;
        int[] tails = new int[size];
        int[] previous = new int[size];
        int length = 0;
        for (int index = 0; index < size; index++) {
            int low = 0;
            int high = length;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (sequence[tails[middle]] < sequence[index]) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            previous[index] = low > 0 ? tails[low - 1] : -1;
            tails[low] = index;
            if (low == length) {
                length++;
            }
        }
        boolean[] keep = new boolean[size];
        Set<Integer> marked = new HashSet<>();
        int cursor = tails[length - 1];
        while (cursor >= 0 && marked.add(cursor)) {
            keep[cursor] = true;
            cursor = previous[cursor];
        }
        return keep;
    }

    private static CompilerException inconsistent(String invariant) {
        return new CompilerException(
                CompilerErrorCodes.COMPILER_OUTPUT_INCONSISTENT,
                invariant,
                "A component ordering invariant is not satisfied.");
    }
}