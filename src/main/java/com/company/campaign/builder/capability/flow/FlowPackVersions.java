package com.company.campaign.builder.capability.flow;

import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.contracts.SchemaVersion;

import java.util.List;

/**
 * The version discriminators and closed codes shared by the T19 Flow node capability packs.
 *
 * <p>Every value here is part of a pack's canonical metadata hash, of a frozen catalog pin or of a resolved
 * component's identity, so a change to any of them is a new capability contract version rather than a silent
 * reinterpretation of an already-produced resolved artifact, Plan or card.
 *
 * <p>The six capability codes are the exact accepted initial Flow node capabilities. They are the whole of
 * the T19 section 4 Flow scope: a Start, Message, Wait, Condition, Reward and End node, each validated
 * node-locally. T20 owns the graph, the edges, reachability and every composition dependency between these
 * nodes; nothing in this package resolves or reasons about them.
 */
public final class FlowPackVersions {

    /** The exact contract version every Flow node pack declares. */
    public static final String CONTRACT_VERSION = "1.0";

    /** The exact pack version stamp of the T19 Flow node packs. */
    public static final String PACK_VERSION = "t19-v1";

    /** The schema version of the pack metadata itself. */
    public static final SchemaVersion SCHEMA_VERSION = SchemaVersion.parse("1.0");

    /** The exact runtime schema version every Flow node pack was built against. */
    public static final String RUNTIME_SCHEMA_VERSION = "campaign-runtime/1.0";

    /** The one composition slot every Flow node pack occupies. */
    public static final String SLOT_CODE = "campaign.flow";

    /**
     * The shared Flow composition slot: an ordered, multi-instance slot.
     *
     * <p>It is ordered because Flow nodes carry a meaningful relative order, and it is not a singleton because
     * a campaign holds many Flow nodes. The slot says nothing about the graph between them.
     */
    public static final CompositionSlot SLOT = new CompositionSlot(SLOT_CODE, true, false);

    /**
     * The frozen preview/runtime compatibility of every Flow node pack.
     *
     * <p>{@code previewSupported} is true because a resolved Flow node projects a safe preview card.
     * {@code publishable} is false because no Journey compiler, schedule, Runtime, worker or Provider path
     * exists in T19 — those belong to T20-T23 — and development evidence is never relabelled as
     * production-ready. This is an honest preview-only declaration, not a claim of runtime support.
     */
    public static final RuntimeCompatibility RUNTIME_COMPATIBILITY =
            new RuntimeCompatibility(RUNTIME_SCHEMA_VERSION, true, false);

    /** The exact version every Flow node pack's in-code evaluation asset declares. */
    public static final String EVALUATION_ASSET_VERSION = "1.0";

    /** The capability code of the Flow Start node. */
    public static final String START = "flow.start";

    /** The capability code of the Flow Message node. */
    public static final String MESSAGE = "flow.message";

    /** The capability code of the Flow Wait node. */
    public static final String WAIT = "flow.wait";

    /** The capability code of the Flow Condition node. */
    public static final String CONDITION = "flow.condition";

    /** The capability code of the Flow Reward node. */
    public static final String REWARD = "flow.reward";

    /** The capability code of the Flow End node. */
    public static final String END = "flow.end";

    /** The exact accepted Flow node capability codes, in declaration order. */
    public static final List<String> CAPABILITY_CODES = List.of(START, MESSAGE, WAIT, CONDITION, REWARD, END);

    private FlowPackVersions() {
    }
}
