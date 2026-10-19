package com.company.campaign.builder.compiler;

import java.util.List;
import java.util.Objects;

/**
 * The closed, immutable, internal change set that turns the trusted Current campaign into the Manifest.
 *
 * <p>It is a compiler output. It is never a Proposal input and no codec, controller or model accepts one;
 * the host applies a stored Plan, never a submitted diff. Every node is a closed typed value, and the
 * class deliberately exposes no execution method, no raw command, no script and no reflection callback — a
 * caller can read a change set but cannot ask it to mutate anything.
 *
 * <p>The class is final, has no public constructor and no public factory, builder or deserializer, so the
 * only producer is this compiler and a forged change set cannot be constructed outside the package.
 *
 * <p>Nodes are grouped {@code ADD}, {@code UPDATE}, {@code MOVE}, {@code DELETE}; each group follows the
 * target or source order. Replaying the groups in that order onto the trusted Current campaign reproduces
 * the Manifest exactly.
 */
public final class ChangeSet {

    private final String formatVersion;
    private final List<ChangeSetNode> nodes;

    ChangeSet(String formatVersion, List<ChangeSetNode> nodes) {
        this.formatVersion = Objects.requireNonNull(formatVersion, "formatVersion");
        this.nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
    }

    /** The ChangeSet document format version. */
    public String formatVersion() {
        return formatVersion;
    }

    /** Every node, in deterministic apply order. */
    public List<ChangeSetNode> nodes() {
        return nodes;
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public int size() {
        return nodes.size();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ChangeSet changeSet
                && Objects.equals(formatVersion, changeSet.formatVersion)
                && Objects.equals(nodes, changeSet.nodes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(formatVersion, nodes);
    }

    @Override
    public String toString() {
        return "ChangeSet[formatVersion=" + formatVersion + ", nodes=" + nodes.size() + ']';
    }
}