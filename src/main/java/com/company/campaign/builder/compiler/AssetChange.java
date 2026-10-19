package com.company.campaign.builder.compiler;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Objects;

/**
 * One actual semantic field change inside a {@link ChangeSetNode.Update}.
 *
 * <p>An update contains only the facts that really changed: a byte-identical normalized payload, resolved
 * reference list, contract version or composition slot is not repeated here. The type is closed, so an
 * update cannot carry a raw command, a script, a callback or any other executable content.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes({
        @JsonSubTypes.Type(AssetChange.CapabilityChanged.class),
        @JsonSubTypes.Type(AssetChange.ContractVersionChanged.class),
        @JsonSubTypes.Type(AssetChange.SlotChanged.class),
        @JsonSubTypes.Type(AssetChange.ValueChanged.class),
        @JsonSubTypes.Type(AssetChange.ReferencesChanged.class)
})
public sealed interface AssetChange {

    /** The bounded field-path code this change reports in the semantic diff. */
    String fieldPath();

    /** The capability code changed. */
    record CapabilityChanged(String capabilityCode) implements AssetChange {
        public CapabilityChanged {
            Objects.requireNonNull(capabilityCode, "capabilityCode");
        }

        @Override
        public String fieldPath() {
            return "capabilityCode";
        }
    }

    /** The pinned contract version changed. */
    record ContractVersionChanged(String contractVersion) implements AssetChange {
        public ContractVersionChanged {
            Objects.requireNonNull(contractVersion, "contractVersion");
        }

        @Override
        public String fieldPath() {
            return "contractVersion";
        }
    }

    /** The occupied composition slot changed. */
    record SlotChanged(CompositionSlot slot) implements AssetChange {
        public SlotChanged {
            Objects.requireNonNull(slot, "slot");
        }

        @Override
        public String fieldPath() {
            return "compositionSlot";
        }
    }

    /** The normalized canonical payload changed. */
    record ValueChanged(CapabilityPayload value) implements AssetChange {
        public ValueChanged {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String fieldPath() {
            return "value";
        }
    }

    /** The resolved catalog reference list changed. */
    record ReferencesChanged(List<ResolvedCatalogReference> references) implements AssetChange {
        public ReferencesChanged {
            references = List.copyOf(Objects.requireNonNull(references, "references"));
        }

        @Override
        public String fieldPath() {
            return "references";
        }
    }
}