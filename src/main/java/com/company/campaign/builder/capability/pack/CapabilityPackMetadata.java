package com.company.campaign.builder.capability.pack;

import com.company.campaign.builder.capability.CapabilityPlanPreviewCard;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The complete, immutable, machine-readable metadata of one frozen capability pack.
 *
 * <p>This is the common contract every T19 pack exposes, and it is capability-agnostic: it names no
 * concrete capability, slot, field, bound or business constant. A pack declares its canonical capability
 * code, its pack and contract versions, the schema version of its metadata, the composition slot it
 * occupies, the safe preview-card kind and contract version it projects, the frozen
 * {@link RuntimeCompatibility} it was built against, a closed {@link CapabilityPayloadContract} of typed
 * payload fields and the {@link CapabilityEvaluationAsset} it pins.
 *
 * <p>It carries no prompt text, no provider credential, no tenant/program/user/DataScope value, no mutable
 * registry handle, no callback, script, class name or arbitrary expression — none of those is representable
 * here, and every declared name is screened against the closed safety vocabulary while the metadata is read.
 *
 * <p>The metadata has exactly one canonical byte form and one content hash. {@link #of} is the only way to
 * build metadata carrying a correct {@code packHash}, and {@link #requireValidPackHash()} re-derives it from
 * the value, so an inconsistent or stale hash pin is refused rather than trusted. The preview-card kind and
 * version are closed codes: a pack may only declare the card this build knows, so an invented projection
 * fails closed instead of entering a plan.
 */
public record CapabilityPackMetadata(
        String capabilityCode,
        String packVersion,
        String contractVersion,
        SchemaVersion schemaVersion,
        String previewCardKind,
        String previewCardVersion,
        CompositionSlot slot,
        RuntimeCompatibility runtimeCompatibility,
        CapabilityPayloadContract payloadContract,
        CapabilityEvaluationAsset evaluationAsset,
        ContentHash packHash) {

    /** The closed set of preview-card kinds a pack may declare. */
    private static final Set<String> PREVIEW_CARD_KINDS = Set.of(CapabilityPlanPreviewCard.CAPABILITY_PREVIEW);

    /** The closed set of preview-card contract versions a pack may declare. */
    private static final Set<String> PREVIEW_CARD_VERSIONS =
            Set.of(CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION);

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern CAPABILITY_CODE = Pattern.compile("^[a-z][a-z0-9.-]{0,63}$");
    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/+-]{0,63}$");

    public CapabilityPackMetadata {
        if (capabilityCode == null || !CAPABILITY_CODE.matcher(capabilityCode).matches()) {
            throw CapabilityPackSafety.invalid("capabilityPackCode");
        }
        if (packVersion == null || !VERSION.matcher(packVersion).matches()) {
            throw CapabilityPackSafety.invalid("capabilityPackVersion");
        }
        if (contractVersion == null || !VERSION.matcher(contractVersion).matches()) {
            throw CapabilityPackSafety.invalid("capabilityPackContractVersion");
        }
        if (schemaVersion == null) {
            throw CapabilityPackSafety.invalid("capabilityPackSchemaVersion");
        }
        if (previewCardKind == null || !PREVIEW_CARD_KINDS.contains(previewCardKind)) {
            throw CapabilityPackSafety.invalid("capabilityPackPreviewCardKind");
        }
        if (previewCardVersion == null || !PREVIEW_CARD_VERSIONS.contains(previewCardVersion)) {
            throw CapabilityPackSafety.invalid("capabilityPackPreviewCardVersion");
        }
        if (slot == null) {
            throw CapabilityPackSafety.invalid("capabilityPackSlot");
        }
        if (runtimeCompatibility == null) {
            throw CapabilityPackSafety.invalid("capabilityPackRuntime");
        }
        if (payloadContract == null) {
            throw CapabilityPackSafety.invalid("capabilityPackPayloadContract");
        }
        if (evaluationAsset == null) {
            throw CapabilityPackSafety.invalid("capabilityPackEvaluationAsset");
        }
    }

    /**
     * Builds one pack metadata and stamps it with its own canonical content hash.
     *
     * <p>The hash covers the metadata with {@code packHash} absent, which is exactly what
     * {@link #recomputePackHash()} derives, so the pin can never be self-referential.
     */
    public static CapabilityPackMetadata of(
            String capabilityCode,
            String packVersion,
            String contractVersion,
            SchemaVersion schemaVersion,
            String previewCardKind,
            String previewCardVersion,
            CompositionSlot slot,
            RuntimeCompatibility runtimeCompatibility,
            CapabilityPayloadContract payloadContract,
            CapabilityEvaluationAsset evaluationAsset) {

        CapabilityPackMetadata unhashed = new CapabilityPackMetadata(capabilityCode, packVersion,
                contractVersion, schemaVersion, previewCardKind, previewCardVersion, slot,
                runtimeCompatibility, payloadContract, evaluationAsset, null);
        return unhashed.withPackHash(unhashed.recomputePackHash());
    }

    /** The same metadata carrying the given hash pin. */
    public CapabilityPackMetadata withPackHash(ContentHash hash) {
        return new CapabilityPackMetadata(capabilityCode, packVersion, contractVersion, schemaVersion,
                previewCardKind, previewCardVersion, slot, runtimeCompatibility, payloadContract,
                evaluationAsset, hash);
    }

    /** The canonical UTF-8 bytes of this metadata with the hash pin excluded. */
    public byte[] canonicalBytes() {
        return CANONICAL_JSON.write(withPackHash(null));
    }

    /** The canonical content hash of this metadata with the hash pin excluded. */
    public ContentHash recomputePackHash() {
        return CANONICAL_JSON.hash(withPackHash(null));
    }

    /** Refuses metadata whose declared hash pin is absent or does not match its own content. */
    public void requireValidPackHash() {
        if (packHash == null || !packHash.equals(recomputePackHash())) {
            throw CapabilityPackSafety.invalid("capabilityPackHash");
        }
    }

    /** Redacted diagnostics: only already-safe machine facts, never a declared field name or fixture id. */
    @Override
    public String toString() {
        return "CapabilityPackMetadata[capabilityCode=" + capabilityCode
                + ", packVersion=" + packVersion
                + ", contractVersion=" + contractVersion
                + ", schemaVersion=" + schemaVersion
                + ", slot=" + slot.code()
                + ", runtime=" + runtimeCompatibility
                + ", fields=" + payloadContract.fields().size()
                + ", fixtures=" + evaluationAsset.allFixtures().size()
                + ']';
    }
}