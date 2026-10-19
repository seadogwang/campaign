package com.company.campaign.action;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * One host-only reconcile or safe-retry command.
 *
 * <p>It carries explicit tenant, actor and authorization evidence, the revision the caller observed and one
 * bounded reason code. It deliberately carries no caller-supplied success fact, budget, request hash, Provider
 * capability, recipient or version, so no operator and no caller can assert an outcome this layer has to
 * believe: the persisted evidence decides.
 *
 * <p>It is never reachable from a model-callable Tool and never from a Builder, Prompt, Skill or Agent path —
 * T23 registers no mutation as a Tool at all.
 */
public record ActionOperatorCommand(
        String tenantId,
        String actionId,
        String actorId,
        Set<String> authorizationScopes,
        long expectedRevision,
        String reasonCode
) {

    /** The authorization scope every host-only reconcile or safe-retry command requires. */
    public static final String SCOPE_OPERATE = "campaign.operate";

    public ActionOperatorCommand {
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireIdentifier(actionId, "actionId");
        ActionSupport.requireIdentifier(actorId, "actorId");
        if (authorizationScopes == null) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_OPERATION_FORBIDDEN, "authorizationScopes");
        }
        Set<String> scopes = new LinkedHashSet<>();
        for (String scope : authorizationScopes) {
            if (scope != null) {
                scopes.add(scope);
            }
        }
        authorizationScopes = Set.copyOf(scopes);
        ActionSupport.requireNonNegative(expectedRevision, "expectedRevision");
        ActionSupport.requireMachineCode(reasonCode, "reasonCode");
    }

    /** One host-only command carrying exactly the one scope this layer recognizes. */
    public static ActionOperatorCommand of(
            String tenantId,
            String actionId,
            String actorId,
            boolean authorized,
            long expectedRevision,
            String reasonCode) {
        return new ActionOperatorCommand(
                tenantId, actionId, actorId, authorized ? Set.of(SCOPE_OPERATE) : Set.of(), expectedRevision,
                reasonCode);
    }

    /** True when the caller actually holds the {@code campaign.operate} authorization. */
    public boolean authorized() {
        return authorizationScopes.contains(SCOPE_OPERATE);
    }

    /** Redacted diagnostics: neither the actor nor the action identity is printed. */
    @Override
    public String toString() {
        return "ActionOperatorCommand[authorized=" + authorized()
                + ", expectedRevision=" + expectedRevision
                + ", reasonCode=" + reasonCode + ']';
    }

    /** True when this command names the exact actor an audit record should attribute its work to. */
    public boolean sameActor(ActionOperatorCommand other) {
        return other != null && Objects.equals(actorId, other.actorId);
    }
}