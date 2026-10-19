package com.company.campaign.evaluation.capabilities.t19;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.shared.ImmutableValues;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The hash-pinned T19 capability evaluation manifest.
 *
 * <p>It names every covered pack by canonical capability code, exact contract version and frozen slot, pins
 * each pack's fixture document by content hash, and pins the one complete-composition document the same way.
 * The manifest's own {@code manifestHash} is the canonical content hash of the manifest with the hash absent,
 * so a hand-edited manifest cannot pass as the reviewed one.
 *
 * <p>It declares exactly one runtime schema version, so a corpus cannot be partly authored against a runtime
 * this build does not support. It carries no template body, no reward secret, no credential and no provider
 * response.
 */
public record T19EvaluationManifest(
        String schemaVersion,
        String assetId,
        String assetVersion,
        String runtimeSchemaVersion,
        List<T19PackAssetReference> packs,
        T19CompositionAssetReference compositions,
        ContentHash manifestHash) {

    /** Largest permitted number of pack references in one manifest. */
    public static final int MAX_PACKS = 32;

    public T19EvaluationManifest {
        if (schemaVersion == null || !T19EvaluationVersions.SCHEMA_VERSION.value().equals(schemaVersion)) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.SCHEMA_UNSUPPORTED,
                    "A T19 evaluation manifest must declare the one supported schema version.");
        }
        if (assetId == null || assetVersion == null) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A T19 evaluation manifest requires a bounded asset identity and version.");
        }
        if (runtimeSchemaVersion == null
                || !T19EvaluationVersions.RUNTIME_SCHEMA_VERSION.equals(runtimeSchemaVersion)) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.SCHEMA_UNSUPPORTED,
                    "A T19 evaluation manifest must declare the one supported runtime schema version.");
        }
        if (packs == null || packs.isEmpty() || packs.size() > MAX_PACKS) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A T19 evaluation manifest requires a bounded, non-empty pack list.");
        }
        List<T19PackAssetReference> copy = new ArrayList<>(packs.size());
        Set<String> codes = new HashSet<>(packs.size());
        Set<String> paths = new HashSet<>(packs.size());
        for (T19PackAssetReference reference : packs) {
            if (reference == null) {
                throw new T19EvaluationException(
                        T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                        "A T19 evaluation manifest must not carry a null pack reference.");
            }
            if (!codes.add(reference.capabilityCode())) {
                throw new T19EvaluationException(
                        T19EvaluationErrorCodes.DUPLICATE_CAPABILITY,
                        "A T19 evaluation manifest must not declare one capability twice.");
            }
            if (!paths.add(reference.fixturesPath())) {
                throw new T19EvaluationException(
                        T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                        "A T19 evaluation manifest must not pin one fixture document twice.");
            }
            copy.add(reference);
        }
        if (compositions == null) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A T19 evaluation manifest requires its complete-composition document.");
        }
        packs = ImmutableValues.freezeList(copy);
    }

    /** The schema version as a parsed value, proving it is supported. */
    public SchemaVersion requireSupportedSchemaVersion() {
        return SchemaVersion.parse(schemaVersion);
    }

    /** The pack reference of one capability, or {@code null} when the manifest does not cover it. */
    public T19PackAssetReference packFor(String capabilityCode) {
        for (T19PackAssetReference reference : packs) {
            if (reference.capabilityCode().equals(capabilityCode)) {
                return reference;
            }
        }
        return null;
    }

    /** Redacted diagnostics: only identity, versions and counts. */
    @Override
    public String toString() {
        return "T19EvaluationManifest[assetId=" + assetId
                + ", assetVersion=" + assetVersion
                + ", packs=" + packs.size()
                + ", runtime=" + runtimeSchemaVersion
                + ", manifestHash=" + manifestHash + ']';
    }
}