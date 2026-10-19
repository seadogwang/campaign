package com.company.campaign.journey.simulation;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The immutable, hash-bound scenario of one Journey simulation.
 *
 * <p>It holds at most one explicit fact per fact code, in deterministic fact-code order, and exposes the
 * canonical scenario hash the simulation report binds. Construction fails closed on a null fact, a repeated
 * fact code and an unbounded scenario, so a scenario cannot silently shadow one fact with another.
 *
 * <p>The scenario carries no repository, clock, member identity, template body, reward secret or provider
 * output; it is a frozen explicit input, not a data source.
 */
public final class ScenarioFacts {

    /** The largest number of facts one scenario may carry. */
    public static final int MAX_FACTS = 256;

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final List<ScenarioFact> facts;
    private final ContentHash scenarioHash;

    private ScenarioFacts(List<ScenarioFact> facts) {
        if (facts == null || facts.size() > MAX_FACTS) {
            throw new SimulationException(SimulationErrorCodes.SCENARIO_INVALID, "facts",
                    "A scenario must carry a bounded fact collection.");
        }
        List<ScenarioFact> copy = new ArrayList<>(facts.size());
        Set<String> codes = new HashSet<>();
        for (ScenarioFact fact : facts) {
            Objects.requireNonNull(fact, "fact");
            if (!codes.add(fact.factCode())) {
                throw new SimulationException(SimulationErrorCodes.SCENARIO_INVALID, "duplicateFactCode",
                        "A scenario must not declare one fact code twice.");
            }
            copy.add(fact);
        }
        copy.sort(ScenarioFact::order);
        this.facts = List.copyOf(copy);
        this.scenarioHash = CANONICAL_JSON.hash(new Material(this.facts));
    }

    /** Builds a scenario from its explicit fact collection. */
    public static ScenarioFacts of(List<ScenarioFact> facts) {
        return new ScenarioFacts(facts);
    }

    /** Builds a scenario from its explicit fact array. */
    public static ScenarioFacts of(ScenarioFact... facts) {
        Objects.requireNonNull(facts, "facts");
        return new ScenarioFacts(List.of(facts));
    }

    /** The empty scenario: no fact is known, so every predicate is missing. */
    public static ScenarioFacts empty() {
        return new ScenarioFacts(List.of());
    }

    /** Every fact, in deterministic fact-code order. */
    public List<ScenarioFact> facts() {
        return facts;
    }

    /** The canonical scenario hash, computed from strict canonical bytes that cover every fact. */
    public ContentHash scenarioHash() {
        return scenarioHash;
    }

    /** The fact declared for the given code, or null when the scenario did not supply it. */
    public ScenarioFact fact(String factCode) {
        for (ScenarioFact fact : facts) {
            if (fact.factCode().equals(factCode)) {
                return fact;
            }
        }
        return null;
    }

    /** The canonical UTF-8 bytes every fact is covered by. */
    public byte[] canonicalBytes() {
        return CANONICAL_JSON.write(new Material(facts));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ScenarioFacts scenario && scenarioHash.equals(scenario.scenarioHash);
    }

    @Override
    public int hashCode() {
        return scenarioHash.hashCode();
    }

    @Override
    public String toString() {
        return "ScenarioFacts[facts=" + facts.size() + ", scenarioHash=" + scenarioHash + ']';
    }

    /** Package-private hash material; it is never public and never exposed to callers. */
    record Material(List<ScenarioFact> facts) {
    }
}
