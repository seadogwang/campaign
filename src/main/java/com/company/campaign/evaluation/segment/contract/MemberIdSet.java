package com.company.campaign.evaluation.segment.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An expected member set, modelled as a mathematical set rather than a list.
 *
 * <p>Member identity is the only thing that matters, so two sets that name the same members are equal
 * whatever order the corpus author wrote them in. The set is therefore stored <em>sorted</em>: equality is
 * order-independent and canonical serialization is stable, without either caller having to remember to
 * normalize first.
 *
 * <p>Duplicates are rejected rather than silently collapsed. A repeated member id means the author wrote
 * a different thing from what they intended, and a set that quietly absorbed it would hide that. The
 * failure carries {@link EvaluationErrorCodes#MEMBER_ID_DUPLICATE} so tooling can point at the offending
 * id instead of reporting a generic parse error.
 *
 * <p>Counting members is never an expected semantic result; the set itself is.
 */
public record MemberIdSet(@JsonValue List<String> memberIds) {

    private static final Pattern MEMBER_ID = Pattern.compile(
            com.company.campaign.contracts.ContractPatterns.REFERENCE_ID);

    /** Sorts by UTF-16 code unit, which is locale-independent and therefore reproducible. */
    private static final Comparator<String> CANONICAL_ORDER = Comparator.naturalOrder();

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public MemberIdSet {
        if (memberIds == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.MEMBER_ID_INVALID,
                    "A member set must be present, even when it is empty");
        }
        List<String> checked = new ArrayList<>(memberIds.size());
        Set<String> seen = new HashSet<>(memberIds.size());
        for (String memberId : memberIds) {
            if (memberId == null || !MEMBER_ID.matcher(memberId).matches()) {
                throw new EvaluationException(
                        EvaluationErrorCodes.MEMBER_ID_INVALID,
                        "Member identifier '" + memberId + "' is not a valid reference identifier");
            }
            if (!seen.add(memberId)) {
                throw new EvaluationException(
                        EvaluationErrorCodes.MEMBER_ID_DUPLICATE,
                        "Member identifier '" + memberId + "' appears more than once in the expected set",
                        Map.of("memberId", memberId));
            }
            checked.add(memberId);
        }
        checked.sort(CANONICAL_ORDER);
        memberIds = List.copyOf(checked);
    }

    /** Builds a set from an unordered collection of member identifiers. */
    public static MemberIdSet of(String... memberIds) {
        return new MemberIdSet(List.of(memberIds));
    }

    public int size() {
        return memberIds.size();
    }

    public boolean contains(String memberId) {
        // Sorted storage makes membership a binary search rather than a linear scan.
        return java.util.Collections.binarySearch(memberIds, memberId, CANONICAL_ORDER) >= 0;
    }
}