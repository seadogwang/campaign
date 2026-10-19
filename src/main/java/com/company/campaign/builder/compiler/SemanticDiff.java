package com.company.campaign.builder.compiler;

import java.util.List;
import java.util.Objects;

/**
 * The immutable, ordered semantic difference between the trusted Current campaign and the resolved Manifest.
 *
 * <p>It is a compiler output: it is never a Proposal input and no codec or controller accepts one. Entries
 * are grouped by change kind — {@code ADD}, {@code UPDATE}, {@code MOVE}, {@code DELETE} — and inside each
 * group they follow the target or source order, so the same compile always produces the same sequence.
 */
public record SemanticDiff(List<DiffEntry> entries) {

    public SemanticDiff {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int count(ChangeKind kind) {
        return (int) entries.stream().filter(entry -> entry.kind() == kind).count();
    }

    @Override
    public String toString() {
        return "SemanticDiff[entries=" + entries.size()
                + ", added=" + count(ChangeKind.ADD)
                + ", updated=" + count(ChangeKind.UPDATE)
                + ", moved=" + count(ChangeKind.MOVE)
                + ", deleted=" + count(ChangeKind.DELETE) + ']';
    }
}