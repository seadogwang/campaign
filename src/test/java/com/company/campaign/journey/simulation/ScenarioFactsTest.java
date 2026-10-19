package com.company.campaign.journey.simulation;

import com.company.campaign.builder.capability.flow.FlowConditionPack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Focused T20 section-4 tests: the immutable, hash-bound scenario fact set. */
class ScenarioFactsTest {

    @Test
    void theScenarioHashIsDeterministicAndOrderIndependent() {
        ScenarioFacts first = ScenarioFacts.of(
                ScenarioFact.known("member.tier", FlowConditionPack.TYPE_STRING, "GOLD"),
                ScenarioFact.unknown("order.count"));
        ScenarioFacts second = ScenarioFacts.of(
                ScenarioFact.unknown("order.count"),
                ScenarioFact.known("member.tier", FlowConditionPack.TYPE_STRING, "GOLD"));

        assertThat(first.scenarioHash()).isEqualTo(second.scenarioHash());
        assertThat(first.canonicalBytes()).isEqualTo(second.canonicalBytes());
    }

    @Test
    void aValueChangeChangesTheScenarioHash() {
        ScenarioFacts first = ScenarioFacts.of(
                ScenarioFact.known("member.tier", FlowConditionPack.TYPE_STRING, "GOLD"));
        ScenarioFacts second = ScenarioFacts.of(
                ScenarioFact.known("member.tier", FlowConditionPack.TYPE_STRING, "SILVER"));

        assertThat(first.scenarioHash()).isNotEqualTo(second.scenarioHash());
    }

    @Test
    void aRepeatedFactCodeIsRefused() {
        assertThatThrownBy(() -> ScenarioFacts.of(
                ScenarioFact.unknown("member.tier"),
                ScenarioFact.error("member.tier", "FACT_SOURCE_UNAVAILABLE")))
                .isInstanceOf(SimulationException.class)
                .hasFieldOrPropertyWithValue("code", SimulationErrorCodes.SCENARIO_INVALID);
    }

    @Test
    void factsAreExposedInDeterministicOrderAndAreImmutable() {
        ScenarioFacts facts = ScenarioFacts.of(
                ScenarioFact.unknown("order.count"),
                ScenarioFact.known("member.tier", FlowConditionPack.TYPE_STRING, "GOLD"));

        assertThat(facts.facts()).extracting(ScenarioFact::factCode)
                .containsExactly("member.tier", "order.count");
        assertThatThrownBy(() -> facts.facts().add(ScenarioFact.unknown("x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theEmptyScenarioCarriesNoFact() {
        assertThat(ScenarioFacts.empty().facts()).isEmpty();
        assertThat(ScenarioFacts.empty().fact("member.tier")).isNull();
    }

    @Test
    void eachOutcomeShapeIsTheOnlyRepresentableOne() {
        assertThatThrownBy(() -> new ScenarioFact("member.tier", ScenarioFactOutcome.UNKNOWN,
                FlowConditionPack.TYPE_STRING, "GOLD", null))
                .isInstanceOf(SimulationException.class);
        assertThatThrownBy(() -> new ScenarioFact("member.tier", ScenarioFactOutcome.ERROR,
                null, null, null))
                .isInstanceOf(SimulationException.class);
        assertThatThrownBy(() -> new ScenarioFact("member.tier", ScenarioFactOutcome.KNOWN,
                FlowConditionPack.TYPE_INTEGER, "01", null))
                .isInstanceOf(SimulationException.class);
        assertThatThrownBy(() -> new ScenarioFact("member.tier", ScenarioFactOutcome.KNOWN,
                "MONEY", "1", null))
                .isInstanceOf(SimulationException.class);
        assertThatThrownBy(() -> ScenarioFact.known("not a fact", FlowConditionPack.TYPE_STRING, "x"))
                .isInstanceOf(SimulationException.class);
    }
}
