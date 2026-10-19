package com.company.campaign.builder.compiler;

/**
 * The closed operation vocabulary of the semantic diff.
 *
 * <p>A relocation is {@link #MOVE}, never a {@link #DELETE} followed by an {@link #ADD}: the two operations
 * carry the same stable identity and the diff reader can therefore tell a reorder from a replacement.
 */
public enum ChangeKind {
    ADD,
    UPDATE,
    DELETE,
    MOVE
}