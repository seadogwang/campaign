package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The frozen per-Case pins of one T13 run.
 *
 * <p>Every field is taken from an already-verified reviewed asset or from a host-owned service object at run
 * start: the case, expected and review hashes the manifest pins, the exact Metadata snapshot identity, the
 * execution-context fixture, the frozen time context and the exact fixture payload, mapping, scope-binding and
 * FX hashes the run will actually execute. Nothing here is derived from the model, from a report or from a
 * comparison.
 */
public record EvaluationCasePins(
        String caseId,
        String category,
        String riskLevel,
        int repeats,
        boolean refinement,
        ContentHash caseHash,
        ContentHash expectedHash,
        ContentHash reviewHash,
        String metadataSnapshotId,
        String metadataSnapshotVersion,
        ContentHash metadataContentHash,
        String executionContextFixture,
        ContentHash executionContextPayloadHash,
        String localeTag,
        Instant asOf,
        String timezone,
        Instant dataWatermark,
        List<FixturePin> fixtures
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public EvaluationCasePins {
        if (caseId == null || !caseId.matches(ContractPatterns.REFERENCE_ID)) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.PIN_MISSING, "A Case pin requires a valid case identifier");
        }
        if (category == null || !category.matches(ContractPatterns.MACHINE_CODE)
                || riskLevel == null || !riskLevel.matches(ContractPatterns.MACHINE_CODE)) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.PIN_MISSING, "A Case pin requires its category and risk-level codes");
        }
        if (repeats < 1) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.PIN_MISSING, "A Case pin requires at least one attempt");
        }
        if (caseHash == null || expectedHash == null || reviewHash == null
                || metadataContentHash == null || executionContextPayloadHash == null) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.PIN_MISSING, "A Case pin requires every reviewed asset hash");
        }
        if (metadataSnapshotId == null || metadataSnapshotVersion == null
                || executionContextFixture == null || localeTag == null || timezone == null
                || asOf == null || dataWatermark == null) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.PIN_MISSING, "A Case pin requires its Metadata, context and time pins");
        }
        if (dataWatermark.isAfter(asOf)) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.PIN_CONTRADICTORY,
                    "A Case data watermark must not be after its asOf instant");
        }
        try {
            java.time.ZoneId.of(timezone);
        } catch (RuntimeException invalidZone) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.PIN_MISSING, "A Case pin requires a valid timezone id");
        }
        if (fixtures == null || fixtures.isEmpty()) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.PIN_MISSING, "A Case pin requires at least one fixture pin");
        }
        fixtures = List.copyOf(new ArrayList<>(fixtures));
        java.util.Set<String> fixtureIds = new java.util.LinkedHashSet<>();
        for (FixturePin fixturePin : fixtures) {
            if (!fixtureIds.add(fixturePin.fixtureId())) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_CONTRADICTORY,
                        "A Case pin must pin each fixture exactly once");
            }
        }
    }

    /** True when this Case is a refinement case that must run through the genuine T12 adapter. */
    public boolean isRefinement() {
        return refinement;
    }

    /** The canonical hash of these Case pins, used as replay-identifier material. */
    public ContentHash contentHash() {
        return CANONICAL_JSON.hash(this);
    }

    /**
     * One frozen fixture pin: the immutable payload plus the exact mapping, scope-binding and FX objects the
     * run executes against.
     */
    public record FixturePin(
            String fixtureId,
            String fixtureVersion,
            ContentHash payloadHash,
            String mappingVersion,
            ContentHash mappingHash,
            String scopeBindingVersion,
            ContentHash scopeBindingHash,
            String fxSnapshotId,
            ContentHash fxHash
    ) {

        public FixturePin {
            if (fixtureId == null || fixtureVersion == null || mappingVersion == null
                    || scopeBindingVersion == null || fxSnapshotId == null) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING, "A fixture pin requires every version reference");
            }
            if (payloadHash == null || mappingHash == null || scopeBindingHash == null || fxHash == null) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING, "A fixture pin requires every content hash");
            }
        }
    }
}