package com.company.campaign.production.provider;

import com.company.campaign.action.ActionKind;

/**
 * The two closed kinds of production Provider this boundary adapts, and the construction-time facts each one
 * fixes.
 *
 * <p>The kind is a property of the adapter, never of a request: it selects the exact business action kind the
 * SPI call belongs to, the provider kind published as evidence and the one route segment the bounded wire
 * contract uses. There is deliberately no third kind and no way to select one from an inbound payload.
 */
public enum ProductionProviderKind {

    /** The message Provider behind the accepted {@code MessageProvider} SPI. */
    MESSAGE(ActionKind.MESSAGE, "message", "production-message-provider"),

    /** The reward Provider behind the accepted {@code RewardProvider} SPI. */
    REWARD(ActionKind.REWARD, "reward", "production-reward-provider");

    private final ActionKind actionKind;
    private final String pathSegment;
    private final String providerKind;

    ProductionProviderKind(ActionKind actionKind, String pathSegment, String providerKind) {
        this.actionKind = actionKind;
        this.pathSegment = pathSegment;
        this.providerKind = providerKind;
    }

    /** The exact business action kind this adapter serves. */
    public ActionKind actionKind() {
        return actionKind;
    }

    /** The one bounded route segment this kind's wire contract uses; never selected by a request. */
    public String pathSegment() {
        return pathSegment;
    }

    /** The provider kind published as bounded, redacted evidence; never a credential or an endpoint. */
    public String providerKind() {
        return providerKind;
    }
}