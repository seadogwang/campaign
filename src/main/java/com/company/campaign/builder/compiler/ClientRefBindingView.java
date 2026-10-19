package com.company.campaign.builder.compiler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The bounded, immutable {@code clientRef -> logicalId} view the compiler threads into capability resolution.
 *
 * <p>It exists for exactly one narrow purpose: an accepted T19 Flow component may declare a reference to a
 * component the <em>same</em> proposal creates, and that reference names the new component by its
 * {@code clientRef} because its final {@code logicalId} does not exist yet. The compiler owns the mapping, so
 * only the compiler — never a caller, a payload or a contract — can decide which client references are
 * declared and what they resolve to.
 *
 * <p>The view is deliberately not a general rewrite facility. A lookup states the exact capability code the
 * reference must resolve to, and answers only when the declared target carries exactly that capability; an
 * undeclared reference, a reference declared under another capability and an unresolved value all answer empty,
 * so the caller's own reference validation still fails closed. Nothing here interprets a payload, reads a
 * repository, a clock or a mutable registry, or exposes a final identity to a model.
 */
public final class ClientRefBindingView {

    private static final ClientRefBindingView EMPTY =
            new ClientRefBindingView(List.of(), Map.of());

    private final Map<String, String> logicalIdByClientRef;
    private final Map<String, String> capabilityByClientRef;

    private ClientRefBindingView(
            List<ClientRefBinding> bindings, Map<String, String> capabilityByClientRef) {
        Map<String, String> logicalIds = new LinkedHashMap<>();
        for (ClientRefBinding binding : bindings) {
            Objects.requireNonNull(binding, "binding");
            if (logicalIds.putIfAbsent(binding.clientRef(), binding.logicalId()) != null) {
                throw new IllegalArgumentException("a binding view must not repeat one client reference");
            }
        }
        Map<String, String> capabilities = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : capabilityByClientRef.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "clientRef");
            Objects.requireNonNull(entry.getValue(), "capabilityCode");
            capabilities.put(entry.getKey(), entry.getValue());
        }
        this.logicalIdByClientRef = Map.copyOf(logicalIds);
        this.capabilityByClientRef = Map.copyOf(capabilities);
    }

    /** The honest empty view: no reference is declared, so every lookup answers empty. */
    public static ClientRefBindingView empty() {
        return EMPTY;
    }

    /**
     * Builds the view from the compiler's own binding list and the declared capability of each client reference.
     *
     * <p>A reference declared in the binding list but not in the capability map is representable but resolves
     * nothing, which is the honest answer for a target whose capability the proposal never stated.
     */
    public static ClientRefBindingView of(
            List<ClientRefBinding> bindings, Map<String, String> capabilityByClientRef) {
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(capabilityByClientRef, "capabilityByClientRef");
        return new ClientRefBindingView(bindings, capabilityByClientRef);
    }

    /** True when {@code value} names a reference this exact view declared. */
    public boolean declares(String value) {
        return value != null && logicalIdByClientRef.containsKey(value);
    }

    /**
     * The final plan-scoped identity of one declared reference, when the declared target carries exactly the
     * expected capability code.
     *
     * <p>The expected capability is part of the question, not of the caller's interpretation: a reference whose
     * declared target is another capability is not bound at all, so the caller's own reference validation sees
     * the unreplaced value and refuses it.
     */
    public Optional<String> boundTarget(String value, String expectedCapabilityCode) {
        if (value == null || expectedCapabilityCode == null) {
            return Optional.empty();
        }
        String logicalId = logicalIdByClientRef.get(value);
        if (logicalId == null || !expectedCapabilityCode.equals(capabilityByClientRef.get(value))) {
            return Optional.empty();
        }
        return Optional.of(logicalId);
    }

    @Override
    public String toString() {
        return "ClientRefBindingView[bindings=" + logicalIdByClientRef.size() + ']';
    }
}