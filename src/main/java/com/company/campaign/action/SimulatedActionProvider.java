package com.company.campaign.action;

import com.company.campaign.shared.canonical.ContentHash;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One deterministic simulated Provider that reproduces the exact failure modes the reliability requirements
 * name: immediate success, definitive non-application, pending then success, unknown then definitive
 * non-application, an effect that succeeds remotely while the response times out, delayed reconciliation, a
 * duplicate submission of one key producing exactly one remote effect, and a changed request hash conflict.
 *
 * <p>It declares {@code production=false} always, so it is explicit non-production fixture evidence and can
 * never satisfy {@code LIVE_READY}. It never contacts a network, never reads a credential and never generates a
 * random value: every response is a pure function of the key, the request material and the injected clock.
 *
 * <p>It holds its remote effect store on one monitor, so a duplicate submission of one key really does produce
 * exactly one effect even under concurrent callers. It is a test and local-use fixture only: T23 wires no real
 * vendor SDK and no production Provider.
 */
public final class SimulatedActionProvider implements MessageProvider, RewardProvider {

    /** The bounded Provider identity every simulated message Provider declares. */
    public static final String MESSAGE_PROVIDER_ID = "simulated-message-provider";

    /** The bounded Provider identity every simulated reward Provider declares. */
    public static final String REWARD_PROVIDER_ID = "simulated-reward-provider";

    /** The default number of reconciliations a delayed remote effect stays pending for. */
    public static final int DEFAULT_DELAYED_RECONCILIATIONS = 2;

    private final String providerId;
    private final ActionKind actionKind;
    private final Clock clock;
    private final Map<String, SimulatedProviderBehaviour> behaviours = new LinkedHashMap<>();
    private final Map<String, RemoteEffect> effects = new LinkedHashMap<>();
    private final Map<String, Integer> submissions = new LinkedHashMap<>();
    private final Map<String, Integer> queries = new LinkedHashMap<>();

    private String capabilityOwnerTenantId;
    private boolean businessKeyIdempotency = true;
    private boolean reliableQueryByKey = true;
    private boolean capabilityThrows;
    private boolean sendThrows;
    private int delayedReconciliations = DEFAULT_DELAYED_RECONCILIATIONS;

    private SimulatedActionProvider(String providerId, ActionKind actionKind, Clock clock) {
        this.providerId = ActionSupport.requireIdentifier(providerId, "providerId");
        this.actionKind = Objects.requireNonNull(actionKind, "actionKind");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** One deterministic simulated message Provider over an injected clock. */
    public static SimulatedActionProvider message(Clock clock) {
        return new SimulatedActionProvider(MESSAGE_PROVIDER_ID, ActionKind.MESSAGE, clock);
    }

    /** One deterministic simulated reward Provider over an injected clock. */
    public static SimulatedActionProvider reward(Clock clock) {
        return new SimulatedActionProvider(REWARD_PROVIDER_ID, ActionKind.REWARD, clock);
    }

    // ------------------------------------------------------------------ deterministic scripting

    /** Plays one exact behaviour for one business idempotency key. */
    public SimulatedActionProvider withBehaviour(String idempotencyKey, SimulatedProviderBehaviour behaviour) {
        behaviours.put(ActionSupport.requireKey(idempotencyKey, "idempotencyKey"),
                Objects.requireNonNull(behaviour, "behaviour"));
        return this;
    }

    /** Declares the owner the capability evidence names, so a cross-owner statement can be reproduced. */
    public SimulatedActionProvider withCapabilityOwner(String ownerTenantId) {
        this.capabilityOwnerTenantId = ownerTenantId;
        return this;
    }

    /** Declares that this Provider supports no business-key idempotency. */
    public SimulatedActionProvider withoutBusinessKeyIdempotency() {
        this.businessKeyIdempotency = false;
        return this;
    }

    /** Declares that this Provider supports no reliable reconciliation. */
    public SimulatedActionProvider withoutReliableQueryByKey() {
        this.reliableQueryByKey = false;
        return this;
    }

    /** Declares that asking this Provider for capability evidence throws instead of answering. */
    public SimulatedActionProvider withThrowingCapability() {
        this.capabilityThrows = true;
        return this;
    }

    /** Declares that every submission throws a bounded transport failure instead of answering. */
    public SimulatedActionProvider withThrowingSend() {
        this.sendThrows = true;
        return this;
    }

    /** Sets how many reconciliations a delayed remote effect stays pending for. */
    public SimulatedActionProvider withDelayedReconciliations(int count) {
        if (count < 0 || count > ActionVersions.MAX_POLICY_ATTEMPTS) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_POLICY_INVALID, "delayedReconciliations");
        }
        this.delayedReconciliations = count;
        return this;
    }

    // ------------------------------------------------------------------ SPI

    @Override
    public ProviderCapability capability(String tenantId) {
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        if (capabilityThrows) {
            throw new ActionProviderException(ActionErrorCodes.ACTION_PROVIDER_CAPABILITY_MISSING);
        }
        String owner = capabilityOwnerTenantId == null ? tenantId : capabilityOwnerTenantId;
        return ProviderCapability.of(
                providerId, owner, actionKind, businessKeyIdempotency, reliableQueryByKey, false);
    }

    @Override
    public ProviderResult send(ProviderRequest request, String idempotencyKey) {
        Objects.requireNonNull(request, "request");
        ActionSupport.requireKey(idempotencyKey, "idempotencyKey");
        synchronized (this) {
            count(submissions, idempotencyKey);
            RemoteEffect existing = effects.get(idempotencyKey);
            if (existing != null) {
                if (!existing.requestHash.equals(request.requestHash())) {
                    // The same business key under different frozen material can never be the same effect.
                    throw new ActionProviderException(ActionErrorCodes.ACTION_IDEMPOTENCY_CONFLICT);
                }
                // A duplicate submission of one key reuses the one remote effect and applies nothing further.
                return response(idempotencyKey, existing);
            }
            SimulatedProviderBehaviour behaviour = behaviours.getOrDefault(
                    idempotencyKey, SimulatedProviderBehaviour.IMMEDIATE_SUCCESS);
            RemoteEffect effect = new RemoteEffect(request.requestHash(), behaviour);
            effects.put(idempotencyKey, effect);
            if (sendThrows) {
                // No remote effect exists and the caller cannot know: the outcome is unknown, never a failure.
                effect.state = RemoteState.UNKNOWN;
                throw new ActionProviderException(ActionErrorCodes.ACTION_PROVIDER_UNAVAILABLE);
            }
            apply(effect, behaviour, idempotencyKey);
            if (behaviour == SimulatedProviderBehaviour.REMOTE_SUCCESS_RESPONSE_TIMEOUT) {
                // The effect applied remotely; the response never arrives, so the caller learns nothing.
                throw new ActionProviderException(ActionErrorCodes.ACTION_PROVIDER_OUTCOME_UNKNOWN);
            }
            if (behaviour == SimulatedProviderBehaviour.CHANGED_REQUEST_HASH_CONFLICT) {
                throw new ActionProviderException(ActionErrorCodes.ACTION_IDEMPOTENCY_CONFLICT);
            }
            return response(idempotencyKey, effect);
        }
    }

    @Override
    public ProviderResult grant(ProviderRequest request, String idempotencyKey) {
        return send(request, idempotencyKey);
    }

    @Override
    public ProviderResult queryByKey(String idempotencyKey) {
        ActionSupport.requireKey(idempotencyKey, "idempotencyKey");
        synchronized (this) {
            count(queries, idempotencyKey);
            RemoteEffect effect = effects.get(idempotencyKey);
            if (effect == null) {
                return ProviderResult.definitivelyNotApplied(idempotencyKey, "PROVIDER_NOT_APPLIED");
            }
            if (effect.state == RemoteState.PENDING) {
                if (effect.pendingRemaining > 0) {
                    effect.pendingRemaining--;
                    return ProviderResult.pending(idempotencyKey, "PROVIDER_PENDING");
                }
                effect.state = RemoteState.APPLIED;
                effect.providerReference = providerReference(idempotencyKey);
                effect.acceptedInstant = clock.instant();
            } else if (effect.state == RemoteState.UNKNOWN) {
                // Reconciliation by the original key is exactly what settles an unknown remote outcome.
                effect.state = RemoteState.NOT_APPLIED;
            }
            return response(idempotencyKey, effect);
        }
    }

    // ------------------------------------------------------------------ bounded observation

    /** The number of actual submissions recorded for one key. */
    public synchronized int submissionCount(String idempotencyKey) {
        return submissions.getOrDefault(idempotencyKey, 0);
    }

    /** The number of actual reconciliation queries recorded for one key. */
    public synchronized int queryCount(String idempotencyKey) {
        return queries.getOrDefault(idempotencyKey, 0);
    }

    /** The number of remote effects that actually exist for one key: zero or one, never more. */
    public synchronized int effectCount(String idempotencyKey) {
        RemoteEffect effect = effects.get(idempotencyKey);
        return effect != null && effect.state == RemoteState.APPLIED ? 1 : 0;
    }

    /** True when this Provider holds any remote effect at all for one key. */
    public synchronized boolean hasRecord(String idempotencyKey) {
        return effects.containsKey(idempotencyKey);
    }

    /** Redacted diagnostics: no credential, token, URL, endpoint or member value is printed. */
    @Override
    public String toString() {
        return "SimulatedActionProvider[providerId=" + providerId
                + ", actionKind=" + actionKind
                + ", production=false" + ']';
    }

    // ------------------------------------------------------------------ internals

    private void apply(
            RemoteEffect effect, SimulatedProviderBehaviour behaviour, String idempotencyKey) {
        switch (behaviour) {
            case IMMEDIATE_SUCCESS -> {
                effect.state = RemoteState.APPLIED;
                effect.providerReference = providerReference(idempotencyKey);
                effect.acceptedInstant = clock.instant();
            }
            case DEFINITIVE_REJECTION -> effect.state = RemoteState.NOT_APPLIED;
            case PENDING_THEN_SUCCESS -> {
                effect.state = RemoteState.PENDING;
                effect.pendingRemaining = 0;
            }
            case DELAYED_RECONCILIATION -> {
                effect.state = RemoteState.PENDING;
                effect.pendingRemaining = delayedReconciliations;
            }
            case UNKNOWN_THEN_NOT_APPLIED -> effect.state = RemoteState.UNKNOWN;
            case REMOTE_SUCCESS_RESPONSE_TIMEOUT -> {
                effect.state = RemoteState.APPLIED;
                effect.providerReference = providerReference(idempotencyKey);
                effect.acceptedInstant = clock.instant();
            }
            case CHANGED_REQUEST_HASH_CONFLICT -> effect.state = RemoteState.NOT_APPLIED;
        }
    }

    private static ProviderResult response(String idempotencyKey, RemoteEffect effect) {
        return switch (effect.state) {
            case APPLIED -> ProviderResult.succeeded(
                    idempotencyKey, effect.providerReference, effect.acceptedInstant, "PROVIDER_APPLIED");
            case NOT_APPLIED -> ProviderResult.definitivelyNotApplied(
                    idempotencyKey, effect.behaviour.reasonCode());
            case PENDING -> ProviderResult.pending(idempotencyKey, effect.behaviour.reasonCode());
            case UNKNOWN -> ProviderResult.unknown(idempotencyKey, effect.behaviour.reasonCode());
        };
    }

    private static String providerReference(String idempotencyKey) {
        // A bounded, deterministic Provider reference derived from the key: never a remote identifier.
        return ActionSupport.derivedId("prvref", List.of(idempotencyKey));
    }

    private static void count(Map<String, Integer> counters, String key) {
        counters.merge(key, 1, Integer::sum);
    }

    /** The closed remote states one simulated effect can hold. */
    private enum RemoteState {
        PENDING,
        APPLIED,
        NOT_APPLIED,
        UNKNOWN
    }

    /** One simulated remote effect; at most one exists per business idempotency key. */
    private static final class RemoteEffect {

        private final ContentHash requestHash;
        private final SimulatedProviderBehaviour behaviour;
        private RemoteState state = RemoteState.UNKNOWN;
        private String providerReference;
        private Instant acceptedInstant;
        private int pendingRemaining;

        private RemoteEffect(ContentHash requestHash, SimulatedProviderBehaviour behaviour) {
            this.requestHash = requestHash;
            this.behaviour = behaviour;
        }
    }
}
