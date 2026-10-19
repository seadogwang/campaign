package com.company.campaign.builder.compiler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The host-owned frozen compile facts one compile is pinned to.
 *
 * <p>It carries two independent, explicitly host-supplied facts about a capability, both keyed by capability
 * code:
 *
 * <ul>
 *   <li>the Gate/readiness projection a compile was pinned to ({@link CapabilityReadiness});</li>
 *   <li>the typed, server-owned frozen inputs a concrete capability contract needs to prove an artifact
 *       ({@link CapabilityInputs}).</li>
 * </ul>
 *
 * <p>It is the only way either kind of fact can reach a capability contract: the compiler passes it through
 * untouched from {@link CompileRequest}, and no part of it is ever derived from a proposal, a model output or
 * a capability payload. An empty context is the honest default — a compile that was pinned to no readiness
 * evidence must not pretend a capability is {@code ACTIVE}, and a compile that pinned no typed input cannot
 * prove an accepted artifact at all.
 *
 * <p>At most one fact of each kind may exist per capability code, so "which readiness or which inputs apply"
 * can never depend on list position or on which duplicate won. Both sets are immutable and deterministically
 * ordered by capability code.
 */
public record CapabilityCompileContext(List<CapabilityReadiness> readiness, List<CapabilityInputs> inputs) {

    private static final CapabilityCompileContext EMPTY = new CapabilityCompileContext(List.of(), List.of());

    private static final Pattern CAPABILITY = Pattern.compile("^[a-z][a-z0-9.-]{0,63}$");

    public CapabilityCompileContext {
        readiness = canonicalReadiness(readiness);
        inputs = canonicalInputs(inputs);
    }

    /** The T15 shape: a compile pinned to readiness facts and no typed frozen inputs. */
    public CapabilityCompileContext(List<CapabilityReadiness> readiness) {
        this(readiness, List.of());
    }

    /** The honest default: no readiness evidence and no typed inputs were pinned. */
    public static CapabilityCompileContext empty() {
        return EMPTY;
    }

    /** The one readiness fact for a capability code, or empty when the compile pinned none. */
    public Optional<CapabilityReadiness> readinessFor(String capabilityCode) {
        if (capabilityCode == null) {
            return Optional.empty();
        }
        for (CapabilityReadiness fact : readiness) {
            if (fact.capabilityCode().equals(capabilityCode)) {
                return Optional.of(fact);
            }
        }
        return Optional.empty();
    }

    /** The one typed frozen input bundle for a capability code, or empty when the compile pinned none. */
    public Optional<CapabilityInputs> inputsFor(String capabilityCode) {
        if (capabilityCode == null) {
            return Optional.empty();
        }
        for (CapabilityInputs bundle : inputs) {
            if (bundle.capabilityCode().equals(capabilityCode)) {
                return Optional.of(bundle);
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "CapabilityCompileContext[readiness=" + readiness.size() + ", inputs=" + inputs.size() + ']';
    }

    private static List<CapabilityReadiness> canonicalReadiness(List<CapabilityReadiness> facts) {
        List<CapabilityReadiness> canonical = CapabilityReadiness.canonical(facts);
        Map<String, CapabilityReadiness> index = new LinkedHashMap<>();
        for (CapabilityReadiness fact : canonical) {
            if (index.putIfAbsent(fact.capabilityCode(), fact) != null) {
                throw new CompilerException(
                        CompilerErrorCodes.COMPILER_FROZEN_INPUT_INVALID,
                        "readinessDuplicate",
                        "A compile must not pin two readiness facts for one capability.");
            }
        }
        return List.copyOf(index.values());
    }

    /**
     * Validates and deterministically orders the typed frozen inputs.
     *
     * <p>A bundle whose capability code is not a bounded canonical code, or a repeated code, is refused
     * outright: the compiler never interprets a bundle's contents, so the code it declares is the only thing
     * that must be sound here, and "which bundle applies" must not depend on list position.
     */
    private static List<CapabilityInputs> canonicalInputs(List<CapabilityInputs> bundles) {
        if (bundles == null) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_FROZEN_INPUT_INVALID,
                    "inputs",
                    "A capability compile context requires an input bundle list.");
        }
        Map<String, CapabilityInputs> index = new LinkedHashMap<>();
        for (CapabilityInputs bundle : bundles) {
            if (bundle == null) {
                throw new CompilerException(
                        CompilerErrorCodes.COMPILER_FROZEN_INPUT_INVALID,
                        "inputs",
                        "A capability compile context must not carry a null input bundle.");
            }
            String code = bundle.capabilityCode();
            if (code == null || !CAPABILITY.matcher(code).matches()) {
                throw new CompilerException(
                        CompilerErrorCodes.COMPILER_FROZEN_INPUT_INVALID,
                        "inputCapabilityCode",
                        "A frozen input bundle requires a bounded canonical capability code.");
            }
            if (index.putIfAbsent(code, bundle) != null) {
                throw new CompilerException(
                        CompilerErrorCodes.COMPILER_FROZEN_INPUT_INVALID,
                        "inputDuplicate",
                        "A compile must not pin two frozen input bundles for one capability.");
            }
        }
        List<CapabilityInputs> ordered = new java.util.ArrayList<>(index.values());
        ordered.sort((left, right) -> left.capabilityCode().compareTo(right.capabilityCode()));
        return List.copyOf(ordered);
    }
}