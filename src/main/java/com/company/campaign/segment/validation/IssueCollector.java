package com.company.campaign.segment.validation;

import com.company.campaign.contracts.Issue;
import com.company.campaign.contracts.IssueSeverity;
import com.company.campaign.shared.canonical.CanonicalJson;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Collects every finding of one validation run and returns them in the one stable order.
 *
 * <p>The order is: pipeline {@link SegmentValidationStage} first, then the original editable tree's
 * pre-order traversal index, then the JSON path, then the stable code. Message text is never the primary
 * key. Body-less issues (a null-input result, a policy failure) sort before node-bound issues inside their
 * stage, and identical issues are collapsed so repeated validation of identical inputs serializes
 * byte-identically.
 *
 * <p>Every issue must be an ERROR: the pipeline publishes no warning or informational finding that could be
 * mistaken for a soft failure, and any issue blocks canonicalization.
 */
final class IssueCollector {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private static final Comparator<StagedIssue> ORDER =
            Comparator.comparingInt((StagedIssue staged) -> staged.stage().ordinal())
                    .thenComparingInt(StagedIssue::traversalIndex)
                    .thenComparing(staged -> staged.path() == null ? "" : staged.path())
                    .thenComparing(staged -> staged.issue().code())
                    .thenComparing(staged -> staged.issue().message())
                    .thenComparing(staged -> CANONICAL_JSON.writeString(staged.issue().details()));

    private final List<StagedIssue> staged = new ArrayList<>();

    /** Records one blocking issue bound to an editable-tree location. */
    void error(
            SegmentValidationStage stage,
            String code,
            String message,
            String path,
            String nodeId,
            int traversalIndex,
            Map<String, Object> details) {

        // The stage name is a safe closed enum and is the machine-observable form of the pipeline order.
        LinkedHashMap<String, Object> bounded = new LinkedHashMap<>();
        if (details != null) {
            bounded.putAll(details);
        }
        bounded.put("stage", stage.name());

        Issue issue = new Issue(
                code, IssueSeverity.ERROR, path, nodeId, null, Objects.requireNonNull(message, "message"),
                bounded);
        staged.add(new StagedIssue(issue, stage, traversalIndex, path));
    }

    /** True when nothing has been recorded. */
    boolean isEmpty() {
        return staged.isEmpty();
    }

    /** The ordered, de-duplicated issue list. */
    List<Issue> ordered() {
        List<StagedIssue> copy = new ArrayList<>(staged);
        copy.sort(ORDER);
        LinkedHashSet<Issue> unique = new LinkedHashSet<>(copy.size());
        for (StagedIssue stagedIssue : copy) {
            unique.add(stagedIssue.issue());
        }
        return List.copyOf(unique);
    }

    private record StagedIssue(Issue issue, SegmentValidationStage stage, int traversalIndex, String path) {
    }
}