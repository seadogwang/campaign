package com.company.campaign.dev.campaignagent;

import com.company.campaign.builder.assembly.CampaignAssemblyContext;
import com.company.campaign.builder.assembly.CampaignToolCatalog;
import com.company.campaign.builder.assembly.CampaignToolRunContext;
import com.company.campaign.builder.authoring.SegmentAuthoringIntegration;
import com.company.campaign.builder.authoring.SegmentAuthoringVersions;
import com.company.campaign.builder.authoring.SegmentRuleBasedCapability;
import com.company.campaign.builder.capability.catalog.MessageClassification;
import com.company.campaign.builder.capability.catalog.MessageTemplateCatalog;
import com.company.campaign.builder.capability.catalog.MessageTemplateVariable;
import com.company.campaign.builder.capability.catalog.MessageTemplateVersion;
import com.company.campaign.builder.capability.catalog.RewardCatalog;
import com.company.campaign.builder.capability.catalog.RewardVersion;
import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.capability.flow.FlowCapabilityPacks;
import com.company.campaign.builder.capability.flow.FlowPackVersions;
import com.company.campaign.builder.capability.governance.GovernanceLimitPack;
import com.company.campaign.builder.capability.message.MessageTemplatePack;
import com.company.campaign.builder.capability.pack.CapabilityPackContract;
import com.company.campaign.builder.capability.pack.CapabilityValueKind;
import com.company.campaign.builder.capability.promotion.PromotionReferencePack;
import com.company.campaign.builder.capability.schedule.ScheduleBasicPack;
import com.company.campaign.builder.compiler.CampaignCompiler;
import com.company.campaign.builder.compiler.CapabilityContract;
import com.company.campaign.builder.compiler.CapabilityReadiness;
import com.company.campaign.builder.compiler.FrozenAuthoringPolicy;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.builder.proposal.ProposalBaseRevisions;
import com.company.campaign.builder.proposal.ProposalClientContext;
import com.company.campaign.builder.proposal.ProposalMode;
import com.company.campaign.dev.segment.CoreLoyaltyDevelopmentProfile;
import com.company.campaign.query.plan.SegmentQueryPlanner;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.segment.validation.SegmentValidationPolicy;
import com.company.campaign.segment.validation.SegmentValidator;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The frozen, <strong>development-only</strong> inputs of the local Campaign Agent host.
 *
 * <p>It reuses the accepted capability packs and the frozen Core Loyalty development authorization boundary, and
 * it fixes a small, visibly development Message and Reward catalog so the Message and Promotion Skills can run
 * end to end without a production catalog. Every value here is development evidence: none of it is production
 * evidence, and none of it satisfies any readiness level.
 *
 * <p>The class reads no clock, no network, no database and no environment, and it holds no static mutable state:
 * every method builds a deterministic immutable value.
 */
public final class DevelopmentCampaignAgentAssets {

    /** The clearly named development profile this host wires. */
    public static final String PROFILE = "campaign-agent-development/1";

    /** The frozen development capability-catalog pin version. */
    public static final String CATALOG_VERSION = "campaign-agent-dev-catalog-1";

    /** The frozen development authoring-policy pin version. */
    public static final String POLICY_VERSION = "campaign-agent-dev-policy-1";

    /** The one development Message Template reference for EMAIL. */
    public static final String TEMPLATE_REFERENCE = "tpl-welcome";

    /** The development Message Template reference for WECHAT inline drafts. */
    public static final String TEMPLATE_REFERENCE_WECHAT = "tpl-wechat-notification";

    /** The development Message Template reference for SMS inline drafts. */
    public static final String TEMPLATE_REFERENCE_SMS = "tpl-sms-notification";

    /** The one development Reward reference. */
    public static final String REWARD_REFERENCE = "rwd-points";

    /**
     * The one development-only Segment readiness reason code.
     *
     * <p>It is deliberately a development state, never {@code ACTIVE}: this slice delivers no approved Gate
     * decision, no production model evidence and no production Preview infrastructure, so the frozen catalog
     * must not claim production readiness for {@code segment.rule-based}.
     */
    public static final String SEGMENT_DEVELOPMENT_REASON = "SEGMENT_DEVELOPMENT_ONLY";

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private DevelopmentCampaignAgentAssets() {
    }

    // ------------------------------------------------------------------ development catalogs

    /**
     * A small, development-only Message Template catalog with one EMAIL reference template and one inline-draft
     * template per selectable channel (WECHAT, SMS), each carrying a bounded {@code bodyText} variable.
     */
    public static MessageTemplateCatalog messageCatalog() {
        return MessageTemplateCatalog.of("tpl-catalog-dev-1", List.of(
                MessageTemplateVersion.of(TEMPLATE_REFERENCE, TEMPLATE_REFERENCE, "tpl-welcome-v1", "EMAIL",
                        List.of(new MessageTemplateVariable("firstName", CapabilityValueKind.STRING, true, 16)),
                        true, true, MessageClassification.PUBLIC),
                MessageTemplateVersion.of(TEMPLATE_REFERENCE_WECHAT, TEMPLATE_REFERENCE_WECHAT,
                        "tpl-wechat-notification-v1", "WECHAT",
                        List.of(new MessageTemplateVariable("bodyText", CapabilityValueKind.STRING, true, 4096)),
                        true, true, MessageClassification.PUBLIC),
                MessageTemplateVersion.of(TEMPLATE_REFERENCE_SMS, TEMPLATE_REFERENCE_SMS,
                        "tpl-sms-notification-v1", "SMS",
                        List.of(new MessageTemplateVariable("bodyText", CapabilityValueKind.STRING, true, 4096)),
                        true, true, MessageClassification.PUBLIC)));
    }

    /** A small, development-only Reward catalog with one enabled, runtime-available version. */
    public static RewardCatalog rewardCatalog() {
        return RewardCatalog.of("rwd-catalog-dev-1", List.of(
                RewardVersion.of(REWARD_REFERENCE, REWARD_REFERENCE, "rwd-points-v1", "POINTS", "CNY",
                        new BigDecimal("10.0000"), new BigDecimal("100.0000"), true, true)));
    }

    // ------------------------------------------------------------------ frozen packs

    public static MessageTemplatePack messagePack() {
        return new MessageTemplatePack(messageCatalog());
    }

    public static PromotionReferencePack promotionPack() {
        return new PromotionReferencePack(rewardCatalog());
    }

    public static ScheduleBasicPack schedulePack() {
        return new ScheduleBasicPack();
    }

    public static GovernanceLimitPack governancePack() {
        return new GovernanceLimitPack();
    }

    /**
     * The accepted {@code segment.rule-based} authoring contract, over the frozen development authorization.
     *
     * <p>It is the exact accepted production contract — no special-case contract exists for the local slice —
     * and it occupies the singleton Campaign audience/segment slot like every other accepted Segment compile.
     * Its readiness is supplied separately as a development-only compile fact, so this catalog change adds the
     * capability without claiming production readiness.
     */
    public static SegmentRuleBasedCapability segmentCapability() {
        AuthorizationService authorizationService = CoreLoyaltyDevelopmentProfile.authorizationService();
        return new SegmentRuleBasedCapability(
                new SegmentValidator(authorizationService),
                SegmentValidationPolicy.standard(),
                new SegmentQueryPlanner(authorizationService));
    }

    /**
     * Every frozen capability contract of the development host: the five single-component packs, the accepted
     * {@code segment.rule-based} contract and the six accepted Flow node packs.
     *
     * <p>The list is a {@link CapabilityContract} list rather than a {@code CapabilityPackContract} list,
     * because the accepted rule-based Segment contract is a plain capability contract of the compiler boundary
     * and carries no pack metadata of its own.
     */
    public static List<CapabilityContract> packs() {
        List<CapabilityContract> packs = new ArrayList<>();
        packs.add(messagePack());
        packs.add(promotionPack());
        packs.add(schedulePack());
        packs.add(governancePack());
        packs.add(segmentCapability());
        packs.addAll(FlowCapabilityPacks.all());
        return List.copyOf(packs);
    }

    /**
     * The one development-only readiness fact of {@code segment.rule-based}.
     *
     * <p>It is host input, built here rather than reused from any test fixture, and it is never
     * {@code ACTIVE}: the state is {@code EXPERIMENTAL}, it rests on no approved decision, and its frozen
     * inputs hash covers this development profile and its frozen catalog/policy pins. A compile pinned to it
     * can author and preview a Segment in development, and nothing more.
     */
    public static CapabilityReadiness segmentReadiness() {
        ContentHash inputsHash = ContentHash.sha256(CANONICAL_JSON.write(List.of(
                PROFILE, CATALOG_VERSION, POLICY_VERSION, SEGMENT_DEVELOPMENT_REASON)));
        return new CapabilityReadiness(
                SegmentAuthoringVersions.CAPABILITY_CODE,
                CapabilityReadiness.EXPERIMENTAL,
                false,
                List.of(SEGMENT_DEVELOPMENT_REASON),
                null,
                inputsHash);
    }

    /** The frozen development capability catalog the assembly context and the Tool handlers pin to. */
    public static FrozenCapabilityCatalog capabilityCatalog() {
        List<CapabilityContract> packs = packs();
        List<String> pins = new ArrayList<>(packs.size());
        for (CapabilityContract contract : packs) {
            pins.add(contract.capabilityCode() + '@' + contract.contractVersion()
                    + '#' + contract.slot() + '!' + contract.enabled());
        }
        ContentHash hash = ContentHash.sha256(CANONICAL_JSON.write(pins));
        return new FrozenCapabilityCatalog(CATALOG_VERSION, hash, new ArrayList<CapabilityContract>(packs));
    }

    /** The frozen development authoring policy, which is advisory-free. */
    public static FrozenAuthoringPolicy authoringPolicy() {
        ContentHash hash = ContentHash.sha256(
                CANONICAL_JSON.write(List.of(POLICY_VERSION, PROFILE)));
        return new FrozenAuthoringPolicy(POLICY_VERSION, hash, request -> List.of());
    }

    /** The frozen T19 pack index a Journey graph is decided against. */
    public static T19PackIndex journeyPackIndex() {
        List<CapabilityPackContract> packs = new java.util.ArrayList<>(FlowCapabilityPacks.all());
        packs.add(messagePack());
        packs.add(promotionPack());
        return T19PackIndex.ofPacks(
                FlowPackVersions.RUNTIME_SCHEMA_VERSION,
                packs.toArray(new CapabilityPackContract[0]));
    }

    /** The accepted Segment authoring integration over the frozen development authorization boundary. */
    public static SegmentAuthoringIntegration segmentIntegration() {
        return new SegmentAuthoringIntegration(
                new CampaignCompiler(CoreLoyaltyDevelopmentProfile.authorizationService()));
    }

    /**
     * Resolves the development template reference code for one canonical inline-draft channel.
     *
     * <p>Each selectable channel maps to exactly one template. An unsupported channel fails closed rather
     * than silently resolving to any other template.
     */
    public static String templateReferenceForChannel(String channel) {
        if (channel == null) {
            throw new IllegalArgumentException("A channel-to-template resolution requires a canonical channel.");
        }
        return switch (channel) {
            case "WECHAT" -> TEMPLATE_REFERENCE_WECHAT;
            case "SMS" -> TEMPLATE_REFERENCE_SMS;
            case "EMAIL" -> TEMPLATE_REFERENCE;
            default -> throw new IllegalArgumentException(
                    "No development template is registered for channel: " + channel);
        };
    }

    // ------------------------------------------------------------------ host-owned run evidence

    /** The host-owned per-run Tool evidence of the development host. */
    public static CampaignToolRunContext toolRunContext(TrustedExecutionContext context) {
        MessageTemplateCatalog messages = messageCatalog();
        RewardCatalog rewards = rewardCatalog();
        MessageTemplatePack messagePack = new MessageTemplatePack(messages);
        PromotionReferencePack promotionPack = new PromotionReferencePack(rewards);
        ScheduleBasicPack schedule = new ScheduleBasicPack();
        GovernanceLimitPack governance = new GovernanceLimitPack();
        FrozenCapabilityCatalog catalog = capabilityCatalog();
        FrozenAuthoringPolicy policy = authoringPolicy();
        T19PackIndex index = journeyPackIndex();
        return new CampaignToolRunContext() {
            @Override
            public MessageTemplateCatalog messageCatalog() {
                return messages;
            }

            @Override
            public RewardCatalog rewardCatalog() {
                return rewards;
            }

            @Override
            public MessageTemplatePack messagePack() {
                return messagePack;
            }

            @Override
            public PromotionReferencePack promotionPack() {
                return promotionPack;
            }

            @Override
            public ScheduleBasicPack schedulePack() {
                return schedule;
            }

            @Override
            public GovernanceLimitPack governancePack() {
                return governance;
            }

            @Override
            public FrozenCapabilityCatalog capabilityCatalog() {
                return catalog;
            }

            @Override
            public FrozenAuthoringPolicy authoringPolicy() {
                return policy;
            }

            @Override
            public TrustedExecutionContext trustedContext() {
                return context;
            }

            @Override
            public T19PackIndex journeyPackIndex() {
                return index;
            }
        };
    }

    // ------------------------------------------------------------------ assembly context

    /** The default development assembly-context factory: a CREATE against the frozen development pins. */
    public static DevelopmentCampaignAssemblyContextFactory assemblyContextFactory() {
        FrozenCapabilityCatalog catalog = capabilityCatalog();
        FrozenAuthoringPolicy policy = authoringPolicy();
        ProposalBaseRevisions revisions = new ProposalBaseRevisions(
                null, catalog.catalogVersion(), catalog.catalogHash(),
                policy.policyVersion(), policy.policyHash());
        return (key, context) -> new CampaignAssemblyContext(
                key.conversationId(), key.runId(), ProposalMode.CREATE, null, revisions,
                ProposalClientContext.EMPTY, CampaignToolCatalog.standard());
    }
}
