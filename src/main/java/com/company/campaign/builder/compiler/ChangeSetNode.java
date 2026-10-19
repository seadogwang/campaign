package com.company.campaign.builder.compiler;

import com.company.campaign.builder.proposal.MoveAnchorKind;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Objects;

/**
 * One closed, typed node of the internal ChangeSet.
 *
 * <p>A node is a value, never behavior. It carries no raw command, no script, no reflection callback and no
 * execution method; the type set is closed and package-private, so only this compiler can produce one and
 * no codec, controller or model can accept one.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes({
        @JsonSubTypes.Type(ChangeSetNode.Add.class),
        @JsonSubTypes.Type(ChangeSetNode.Update.class),
        @JsonSubTypes.Type(ChangeSetNode.Delete.class),
        @JsonSubTypes.Type(ChangeSetNode.Move.class)
})
public sealed interface ChangeSetNode {

    /** The stable identity this node addresses. */
    String logicalId();

    /** A component that must exist in the target state but not in the current one. */
    record Add(
            String logicalId,
            String capabilityCode,
            String contractVersion,
            CompositionSlot slot,
            CapabilityPayload value,
            List<ResolvedCatalogReference> references) implements ChangeSetNode {

        public Add {
            Objects.requireNonNull(logicalId, "logicalId");
            Objects.requireNonNull(capabilityCode, "capabilityCode");
            Objects.requireNonNull(contractVersion, "contractVersion");
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(value, "value");
            references = List.copyOf(Objects.requireNonNull(references, "references"));
        }

        /** The component this add installs. */
        ResolvedComponent component() {
            return new ResolvedComponent(
                    logicalId, capabilityCode, contractVersion, slot, value, references);
        }
    }

    /** An existing component whose semantic facts changed; only the changed facts are present. */
    record Update(String logicalId, List<AssetChange> changes) implements ChangeSetNode {
        public Update {
            Objects.requireNonNull(logicalId, "logicalId");
            changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
            if (changes.isEmpty()) {
                throw new IllegalArgumentException("an update must carry at least one change");
            }
        }
    }

    /** A component that exists in the current state but not in the target one. */
    record Delete(String logicalId, String capabilityCode) implements ChangeSetNode {
        public Delete {
            Objects.requireNonNull(logicalId, "logicalId");
            Objects.requireNonNull(capabilityCode, "capabilityCode");
        }
    }

    /** A relocation of an existing component against exactly one stable relative anchor. */
    record Move(String logicalId, MoveAnchorKind anchorKind, String anchorLogicalId) implements ChangeSetNode {
        public Move {
            Objects.requireNonNull(logicalId, "logicalId");
            Objects.requireNonNull(anchorKind, "anchorKind");
            Objects.requireNonNull(anchorLogicalId, "anchorLogicalId");
            if (logicalId.equals(anchorLogicalId)) {
                throw new IllegalArgumentException("a move must not anchor to itself");
            }
        }
    }
}