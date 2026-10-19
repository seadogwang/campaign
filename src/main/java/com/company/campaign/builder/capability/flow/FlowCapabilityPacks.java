package com.company.campaign.builder.capability.flow;

import com.company.campaign.builder.capability.pack.CapabilityPackContract;

import java.util.List;

/**
 * The six accepted T19 Flow node capability packs, in their declared order.
 *
 * <p>This is the one place a host reads the whole Flow node pack set from: every pack is immutable, holds no
 * mutable state and is a pure function of its request, so a host may pin them into a frozen capability catalog
 * directly. The set is exactly the T19 section 4 Flow scope — Start, Message, Wait, Condition, Reward and End
 * — and nothing here claims a graph, a schedule, a publication or a Runtime for them.
 */
public final class FlowCapabilityPacks {

    private static final List<CapabilityPackContract> PACKS = List.of(
            new FlowStartPack(),
            new FlowMessagePack(),
            new FlowWaitPack(),
            new FlowConditionPack(),
            new FlowRewardPack(),
            new FlowEndPack());

    private FlowCapabilityPacks() {
    }

    /** Every accepted Flow node pack, in declaration order. */
    public static List<CapabilityPackContract> all() {
        return PACKS;
    }
}