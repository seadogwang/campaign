package com.company.campaign.runtime.testsupport;

import com.company.campaign.runtime.RuntimeFactPort;
import com.company.campaign.runtime.RuntimeFactRequest;
import com.company.campaign.runtime.RuntimeFactResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A deterministic, scriptable {@link RuntimeFactPort} for T22 tests.
 *
 * <p>It answers exactly the fact a test declared for a fact code and records the last request, so a test can
 * prove the exact predicate the worker asked about. A fact code the test did not declare is honestly unknown
 * rather than fabricated, and the fixture holds no member data, reaches no database and calls no provider.
 */
public final class ScriptedFactPort implements RuntimeFactPort {

    private final Map<String, RuntimeFactResult> answers = new LinkedHashMap<>();
    private Behaviour behaviour;
    private Instant observedAt = Instant.EPOCH;
    private RuntimeFactRequest lastRequest;
    private int calls;

    /** One behaviour a scripted port answers with. */
    @FunctionalInterface
    public interface Behaviour {
        RuntimeFactResult answer(RuntimeFactRequest request);
    }

    public ScriptedFactPort() {
        this.behaviour = null;
    }

    public ScriptedFactPort(Behaviour behaviour) {
        this.behaviour = Objects.requireNonNull(behaviour, "behaviour");
    }

    @Override
    public RuntimeFactResult readFact(RuntimeFactRequest request) {
        this.lastRequest = request;
        this.calls++;
        if (behaviour != null) {
            return behaviour.answer(request);
        }
        RuntimeFactResult declared = answers.get(request.factCode());
        // An undeclared fact code is honestly unknown, never a fabricated value and never FALSE.
        return declared != null ? declared : RuntimeFactResult.unknown(request.factCode(), observedAt);
    }

    /** Declares the exact answer one fact code is read as. */
    public ScriptedFactPort declare(String factCode, RuntimeFactResult result) {
        answers.put(Objects.requireNonNull(factCode, "factCode"), Objects.requireNonNull(result, "result"));
        return this;
    }

    /** Sets the observation instant an undeclared fact answer carries. */
    public ScriptedFactPort observedAt(Instant instant) {
        this.observedAt = Objects.requireNonNull(instant, "instant");
        return this;
    }

    /** Replaces the behaviour this port answers with. */
    public ScriptedFactPort behave(Behaviour replaced) {
        this.behaviour = Objects.requireNonNull(replaced, "replaced");
        return this;
    }

    /** The last request this port received, or {@code null} when it received none. */
    public RuntimeFactRequest lastRequest() {
        return lastRequest;
    }

    /** The number of times this port was called. */
    public int calls() {
        return calls;
    }

    /** A behaviour that cannot answer at all, so the node must treat it as a technical failure. */
    public static Behaviour failing() {
        return request -> {
            throw new IllegalStateException("scripted fact host unavailable");
        };
    }

    /** A behaviour that answers one known typed value. */
    public static Behaviour value(String valueType, String value) {
        return request -> RuntimeFactResult.known(request.factCode(), valueType, value, Instant.EPOCH);
    }

    /** A behaviour that answers one bounded technical timeout. */
    public static Behaviour timeout() {
        return request -> RuntimeFactResult.timeout(request.factCode(), Instant.EPOCH);
    }

    /** Redacted diagnostics: the fixture holds no owner value. */
    @Override
    public String toString() {
        return "ScriptedFactPort[calls=" + calls + ']';
    }
}