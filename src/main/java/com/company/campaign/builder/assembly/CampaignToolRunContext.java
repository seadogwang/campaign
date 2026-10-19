package com.company.campaign.builder.assembly;

import com.company.campaign.builder.capability.catalog.MessageTemplateCatalog;
import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.capability.catalog.RewardCatalog;
import com.company.campaign.builder.capability.governance.GovernanceLimitPack;
import com.company.campaign.builder.capability.message.MessageTemplatePack;
import com.company.campaign.builder.capability.promotion.PromotionReferencePack;
import com.company.campaign.builder.capability.schedule.ScheduleBasicPack;
import com.company.campaign.builder.compiler.FrozenAuthoringPolicy;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.security.context.TrustedExecutionContext;

/**
 * The host-owned, per-run evidence a Campaign Tool handler may read.
 *
 * <p>Every accessor is injected by the host run; nothing here is read from a typed Tool request. The immutable
 * catalogs, the accepted capability packs, the frozen compile inputs and the accepted T02 trusted context are
 * host facts, so a model cannot supply, override or weaken them by shaping a {@link CampaignToolRequest}. The
 * interface is read-only: a handler can consume evidence but cannot mutate a run.
 *
 * <p>The accepted capability packs are exposed here so the <em>handler</em> is the code that really runs the
 * protected operation: {@code message.*} drives the accepted {@code message.template} pack, {@code reward.*}
 * drives the accepted {@code promotion.reference} pack, {@code schedule.*} drives the accepted
 * {@code schedule.basic} pack and {@code governance.*} drives the accepted {@code governance.limit} pack. A
 * handler whose evidence is missing fails closed with {@code CAMPAIGN_TOOL_EVIDENCE_MISSING} rather than
 * throwing.
 */
public interface CampaignToolRunContext {

    /** The exact host-supplied immutable Message Template catalog of this run, or null when absent. */
    default MessageTemplateCatalog messageCatalog() {
        return null;
    }

    /** The exact host-supplied immutable Reward catalog of this run, or null when absent. */
    default RewardCatalog rewardCatalog() {
        return null;
    }

    /** The accepted {@code message.template} pack of this run, or null when absent. */
    default MessageTemplatePack messagePack() {
        return null;
    }

    /** The accepted {@code promotion.reference} pack of this run, or null when absent. */
    default PromotionReferencePack promotionPack() {
        return null;
    }

    /** The accepted {@code schedule.basic} pack of this run, or null when absent. */
    default ScheduleBasicPack schedulePack() {
        return null;
    }

    /** The accepted {@code governance.limit} pack of this run, or null when absent. */
    default GovernanceLimitPack governancePack() {
        return null;
    }

    /** The frozen capability catalog a pack request is pinned to, or null when absent. */
    default FrozenCapabilityCatalog capabilityCatalog() {
        return null;
    }

    /** The frozen authoring policy a pack request is pinned to, or null when absent. */
    default FrozenAuthoringPolicy authoringPolicy() {
        return null;
    }

    /** The accepted T02 trusted execution context of this run, or null when absent. */
    default TrustedExecutionContext trustedContext() {
        return null;
    }

    /**
     * The frozen T19 pack index a Journey graph is decided against, or null when the host supplied none.
     *
     * <p>It is a host fact like every other accessor here: a model cannot supply, override or weaken it by
     * shaping a {@link CampaignToolRequest}, and a Journey handler whose index is absent fails closed rather
     * than validating a graph against a mutable registry.
     */
    default T19PackIndex journeyPackIndex() {
        return null;
    }
}