package com.company.campaign.builder.compiler;

import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The host-owned readiness fact of one Gate-gated capability, as a frozen compile input.
 *
 * <p>It is the compiler's neutral projection of the accepted T14 readiness evidence. The compiler package
 * deliberately reaches no Gate, evaluation or capability package, so this type re-states the closed
 * {@code ACTIVE}/{@code EXPERIMENTAL}/{@code EVALUATION_REQUIRED} vocabulary instead of importing it; the
 * concrete capability packs the projector into this shape outside the compiler boundary.
 *
 * <p>Readiness is <em>host</em> input. It is never read from a capability payload, a model output or a client
 * request: a proposal that declares its own readiness is refused before any capability contract runs.
 *
 * <p>{@link #activeReady()} is true for exactly the {@code ACTIVE} state and for nothing else, so a
 * contradictory "experimental but ready" fact is not representable. Only an {@code ACTIVE} fact may rest on
 * an approved decision hash, and only an {@code ACTIVE} fact requires a current Gate-input hash.
 */
public record CapabilityReadiness(
        String capabilityCode,
        String state,
        boolean activeReady,
        List<String> reasonCodes,
        ContentHash approvedDecisionHash,
        ContentHash currentInputsHash) {

    /** The one state in which a production Apply/Publish/Runtime path may consume the capability. */
    public static final String ACTIVE = "ACTIVE";

    /** No approved {@code PASSED} decision covers the capability: development authoring only. */
    public static final String EXPERIMENTAL = "EXPERIMENTAL";

    /** A gated input changed since the latest approved {@code PASSED} decision. */
    public static final String EVALUATION_REQUIRED = "EVALUATION_REQUIRED";

    private static final Pattern CAPABILITY = Pattern.compile("^[a-z][a-z0-9.-]{0,63}$");
    private static final Pattern REASON_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    /** Maximum bounded reason codes one readiness fact may carry. */
    static final int MAX_REASON_CODES = 16;

    public CapabilityReadiness {
        if (capabilityCode == null || !CAPABILITY.matcher(capabilityCode).matches()) {
            throw invalid("readinessCapabilityCode");
        }
        if (!ACTIVE.equals(state) && !EXPERIMENTAL.equals(state) && !EVALUATION_REQUIRED.equals(state)) {
            throw invalid("readinessState");
        }
        if (activeReady != ACTIVE.equals(state)) {
            throw invalid("readinessActiveReady");
        }
        List<String> source = reasonCodes == null ? List.of() : reasonCodes;
        if (source.size() > MAX_REASON_CODES) {
            throw invalid("readinessReasonCodes");
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String code : source) {
            if (code == null || !REASON_CODE.matcher(code).matches()) {
                throw invalid("readinessReasonCodes");
            }
            distinct.add(code);
        }
        reasonCodes = List.copyOf(distinct);
        if (ACTIVE.equals(state) && approvedDecisionHash == null) {
            // Only an ACTIVE fact must rest on an approved decision. A non-ACTIVE fact may carry the hash of
            // the non-PASSED or stale decision that kept it non-ACTIVE, and it may carry none at all.
            throw invalid("readinessApprovedDecision");
        }
    }

    /** True when this fact rests on an approved Gate decision. */
    public boolean hasApprovedDecision() {
        return approvedDecisionHash != null;
    }

    /** True only for the one production-ready state. */
    public boolean active() {
        return ACTIVE.equals(state);
    }

    private static CompilerException invalid(String invariant) {
        return new CompilerException(
                CompilerErrorCodes.COMPILER_FROZEN_INPUT_INVALID,
                invariant,
                "A capability readiness fact must be a bounded, self-consistent machine fact.");
    }

    /** A bounded diagnostic; no trusted value is ever printed. */
    @Override
    public String toString() {
        return "CapabilityReadiness[capabilityCode=" + capabilityCode
                + ", state=" + state
                + ", activeReady=" + activeReady
                + ", reasons=" + reasonCodes.size() + ']';
    }

    /** A defensive, duplicate-free copy in deterministic order. */
    static List<CapabilityReadiness> canonical(List<CapabilityReadiness> facts) {
        List<CapabilityReadiness> copy = new ArrayList<>(Objects.requireNonNull(facts, "facts").size());
        for (CapabilityReadiness fact : facts) {
            copy.add(Objects.requireNonNull(fact, "fact"));
        }
        copy.sort((left, right) -> left.capabilityCode().compareTo(right.capabilityCode()));
        return List.copyOf(copy);
    }
}