package com.company.campaign.builder.refine;

import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.segment.dsl.contract.SegmentDslSchemaVersions;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The one bounded, versioned, atomic refinement patch contract.
 *
 * <p>It carries the exact refinement schema version, the required {@code currentDslHash} of the editable T05
 * document it was authored against, and one to a bounded maximum of ordered operations. Construction validates
 * the whole model and re-proves the declared {@link #patchFingerprint()}, so neither a forged fingerprint nor a
 * contradictory patch can be built directly or read from a resource.
 *
 * <p>The patch is atomic: the editor either produces a complete refined document or refuses with one bounded
 * failure and publishes no partial DSL. Each operation carries a stable {@code opId} for deterministic issue
 * and audit references, and every target is addressed by stable node identity — there is no positional field in
 * the contract at all.
 *
 * <p>The fingerprint is canonical and deterministic. It is the only patch-derived value the orchestration
 * result and its audit projection may carry: the fingerprint identifies the edit without exposing the patch,
 * the DSL or any operand.
 */
public record SegmentRefinementPatch(
        SchemaVersion schemaVersion,
        ContentHash currentDslHash,
        List<SegmentRefinementOperation> operations,
        ContentHash patchFingerprint
) {

    public SegmentRefinementPatch {
        SegmentDslSchemaVersions.requireSupported(schemaVersion);
        if (!ContractVersions.isCurrent(schemaVersion)) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.SCHEMA_VERSION_UNSUPPORTED,
                    "A refinement patch requires the exact current schema version");
        }
        if (currentDslHash == null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.NULL_FIELD, "A refinement patch requires its base DSL hash");
        }
        operations = requireOperations(operations);
        ContentHash expected = SegmentRefinementWire.fingerprint(
                new Material(schemaVersion, currentDslHash, operations));
        if (patchFingerprint == null || !patchFingerprint.equals(expected)) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.FINGERPRINT_MISMATCH,
                    "A patch fingerprint must be the canonical fingerprint of its semantic fields");
        }
    }

    /** Publishes a patch: computes the canonical fingerprint from explicit semantic material, then verifies it. */
    public static SegmentRefinementPatch publish(
            SchemaVersion schemaVersion,
            ContentHash currentDslHash,
            List<SegmentRefinementOperation> operations) {
        List<SegmentRefinementOperation> copy = requireOperations(operations);
        ContentHash fingerprint = SegmentRefinementWire.fingerprint(
                new Material(schemaVersion, currentDslHash, copy));
        return new SegmentRefinementPatch(schemaVersion, currentDslHash, copy, fingerprint);
    }

    /** The exact material this patch's {@link #patchFingerprint()} covers. */
    Material hashMaterial() {
        return new Material(schemaVersion, currentDslHash, operations);
    }

    /** The ordered stable identifiers of this patch's operations, for deterministic issue references. */
    public List<String> operationIds() {
        List<String> ids = new ArrayList<>(operations.size());
        for (SegmentRefinementOperation operation : operations) {
            ids.add(operation.operationId());
        }
        return List.copyOf(ids);
    }

    /** The exact material the canonical patch fingerprint covers, excluding the fingerprint field itself. */
    record Material(
            SchemaVersion schemaVersion,
            ContentHash currentDslHash,
            List<SegmentRefinementOperation> operations
    ) {

        Material {
            operations = operations == null ? null : List.copyOf(operations);
        }
    }

    private static List<SegmentRefinementOperation> requireOperations(List<SegmentRefinementOperation> operations) {
        if (operations == null || operations.isEmpty()) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.LIMIT_EXCEEDED,
                    "A refinement patch requires at least one operation");
        }
        if (operations.size() > RefinementLimits.MAX_OPERATIONS) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.LIMIT_EXCEEDED,
                    "A refinement patch exceeds its operation bound");
        }
        Set<String> operationIds = new LinkedHashSet<>();
        for (SegmentRefinementOperation operation : operations) {
            if (operation == null) {
                throw SegmentRefinementException.of(
                        SegmentRefinementErrorCodes.NULL_FIELD, "A refinement patch contains a null operation");
            }
            if (!operationIds.add(operation.operationId())) {
                throw SegmentRefinementException.of(
                        SegmentRefinementErrorCodes.DUPLICATE_OPERATION_ID,
                        "A refinement patch repeats a stable operation identifier");
            }
        }
        return List.copyOf(operations);
    }
}