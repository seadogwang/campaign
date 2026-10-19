package com.company.campaign.builder.compiler;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.Issue;
import com.company.campaign.contracts.IssueSeverity;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The explicit, immutable authoring policy a compile is pinned to.
 *
 * <p>The policy version and content hash are frozen inputs supplied by the host, together with one
 * deterministic validation port. Because the policy is an input rather than a lookup, a rule change is a
 * new version and a new hash — never a silent reinterpretation of an already-produced Manifest.
 *
 * <p>Validator output is bounded and normalized here: at most {@link #MAX_ISSUES} issues, in the order the
 * validator returned them, with no null element. A validator that throws, returns null or overflows the
 * bound fails the compile closed instead of producing a partially evaluated policy.
 */
public record FrozenAuthoringPolicy(
        String policyVersion,
        ContentHash policyHash,
        AuthoringPolicyValidator validator) {

    /** Maximum issues one policy evaluation may report; beyond this the compile fails closed. */
    public static final int MAX_ISSUES = 64;

    private static final Pattern VERSION = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public FrozenAuthoringPolicy {
        if (policyVersion == null || !VERSION.matcher(policyVersion).matches()) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_FROZEN_INPUT_INVALID,
                    "policyVersion",
                    "A frozen authoring policy requires a bounded canonical version.");
        }
        Objects.requireNonNull(policyHash, "policyHash");
        Objects.requireNonNull(validator, "validator");
    }

    /**
     * Runs the frozen validator exactly once and returns its ordered, bounded issues.
     *
     * @throws CompilerException with {@code COMPILER_POLICY_VIOLATION} when the validator reports a blocking
     *         issue; with {@code COMPILER_CAPABILITY_CONTRACT_INVALID} when it returns a malformed result.
     */
    List<Issue> evaluate(AuthoringValidationRequest request) {
        List<Issue> issues;
        try {
            issues = validator.validate(request);
        } catch (RuntimeException exception) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_CAPABILITY_CONTRACT_INVALID,
                    "authoringPolicyValidator",
                    "The frozen authoring policy validator failed.");
        }
        if (issues == null || issues.size() > MAX_ISSUES) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_CAPABILITY_CONTRACT_INVALID,
                    "authoringPolicyValidatorResult",
                    "The frozen authoring policy validator must return a bounded issue list.");
        }
        List<Issue> ordered = new ArrayList<>(issues.size());
        boolean blocking = false;
        for (Issue issue : issues) {
            if (issue == null) {
                throw new CompilerException(
                        CompilerErrorCodes.COMPILER_CAPABILITY_CONTRACT_INVALID,
                        "authoringPolicyValidatorResult",
                        "The frozen authoring policy validator must not return a null issue.");
            }
            if (issue.severity() == IssueSeverity.ERROR) {
                blocking = true;
            }
            ordered.add(issue);
        }
        if (blocking) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_POLICY_VIOLATION,
                    "authoringPolicy",
                    "The frozen authoring policy rejected the resolved campaign.");
        }
        return List.copyOf(ordered);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FrozenAuthoringPolicy policy
                && Objects.equals(policyVersion, policy.policyVersion)
                && Objects.equals(policyHash, policy.policyHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(policyVersion, policyHash);
    }

    @Override
    public String toString() {
        return "FrozenAuthoringPolicy[version=" + policyVersion + ", hash=" + policyHash + ']';
    }
}