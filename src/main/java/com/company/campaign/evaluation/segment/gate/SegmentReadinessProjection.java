package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The deterministic capability-readiness projection of one Gate-gated capability.
 *
 * <p>It is derived, never stored trust: the state, the bounded reason codes, the approved decision hash the
 * state rests on and the canonical hash of the current gated inputs. {@code aiReady} is true only for the
 * exact latest approved {@code PASSED} pins, and it is a projection for later Campaign integration — it
 * activates no other capability and no production path.
 */
public record SegmentReadinessProjection(
        String readinessSchemaVersion,
        String capabilityId,
        CapabilityState state,
        boolean aiReady,
        List<String> reasonCodes,
        ContentHash approvedDecisionHash,
        ContentHash currentInputsHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public SegmentReadinessProjection {
        if (!GateVersions.READINESS_SCHEMA_VERSION.equals(readinessSchemaVersion)) {
            throw GateException.of(
                    GateErrorCodes.CAPABILITY_NOT_GATED,
                    "A readiness projection must declare the exact T14 readiness contract version");
        }
        if (capabilityId == null) {
            throw GateException.of(
                    GateErrorCodes.CAPABILITY_NOT_GATED, "A readiness projection requires its capability id");
        }
        if (state == null) {
            throw GateException.of(
                    GateErrorCodes.CAPABILITY_NOT_GATED, "A readiness projection requires its state");
        }
        List<String> source = reasonCodes == null ? List.of() : reasonCodes;
        Set<String> distinct = new LinkedHashSet<>();
        for (String code : source) {
            if (code == null || !code.matches(ContractPatterns.MACHINE_CODE)) {
                throw GateException.of(
                        GateErrorCodes.CAPABILITY_NOT_GATED,
                        "A readiness projection reason code is not bounded");
            }
            distinct.add(code);
        }
        reasonCodes = List.copyOf(distinct);
        if (aiReady != (state == CapabilityState.ACTIVE)) {
            throw GateException.of(
                    GateErrorCodes.CAPABILITY_NOT_GATED,
                    "Only an exactly-pinned ACTIVE projection may report Campaign AI_READY");
        }
        if (state == CapabilityState.ACTIVE && approvedDecisionHash == null) {
            throw GateException.of(
                    GateErrorCodes.CAPABILITY_NOT_GATED,
                    "Only a projection resting on an approved decision may be ACTIVE");
        }
    }

    /** Builds a projection and stamps it with its own canonical hash. */
    public static SegmentReadinessProjection of(
            String capabilityId,
            CapabilityState state,
            boolean aiReady,
            List<String> reasonCodes,
            ContentHash approvedDecisionHash,
            ContentHash currentInputsHash) {
        return new SegmentReadinessProjection(
                GateVersions.READINESS_SCHEMA_VERSION, capabilityId, state, aiReady,
                reasonCodes == null ? List.of() : new ArrayList<>(reasonCodes),
                approvedDecisionHash, currentInputsHash);
    }

    /** The canonical hash of this projection, for deterministic reporting. */
    public ContentHash contentHash() {
        return CANONICAL_JSON.hash(this);
    }
}