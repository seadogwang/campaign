package com.company.campaign.builder.compiler;

import com.company.campaign.contracts.Issue;

import java.util.List;

/**
 * The deterministic validation port a {@link FrozenAuthoringPolicy} owns.
 *
 * <p>A validator receives frozen facts and returns an ordered, bounded issue list. It must be a pure
 * function of its request: the same request must always produce the same issues in the same order. A
 * validator that throws, returns null, returns an unbounded list or returns a null element fails the
 * compile closed.
 */
@FunctionalInterface
public interface AuthoringPolicyValidator {

    List<Issue> validate(AuthoringValidationRequest request);
}