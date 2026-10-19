package com.company.campaign.builder.assembly.testsupport;

import com.company.campaign.builder.assembly.CampaignArtifactAdapter;
import com.company.campaign.builder.assembly.CampaignAssemblyContext;
import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.assembly.CampaignToolAdmission;
import com.company.campaign.builder.assembly.CampaignToolCatalog;
import com.company.campaign.builder.assembly.CampaignToolInvoker;
import com.company.campaign.builder.assembly.CampaignToolRunContext;
import com.company.campaign.builder.assembly.CampaignToolRequest;
import com.company.campaign.builder.assembly.CampaignToolCall;
import com.company.campaign.builder.assembly.StandardCampaignToolHandlers;
import com.company.campaign.builder.capability.catalog.MessageClassification;
import com.company.campaign.builder.capability.catalog.MessageTemplateCatalog;
import com.company.campaign.builder.capability.catalog.MessageTemplateVariable;
import com.company.campaign.builder.capability.catalog.MessageTemplateVersion;
import com.company.campaign.builder.capability.catalog.RewardCatalog;
import com.company.campaign.builder.capability.catalog.RewardVersion;
import com.company.campaign.builder.capability.governance.GovernanceLimitPack;
import com.company.campaign.builder.capability.governance.GovernanceLimitVersions;
import com.company.campaign.builder.capability.message.MessageTemplatePack;
import com.company.campaign.builder.capability.pack.CapabilityValueKind;
import com.company.campaign.builder.capability.promotion.PromotionReferencePack;
import com.company.campaign.builder.capability.schedule.ScheduleBasicForm;
import com.company.campaign.builder.capability.schedule.ScheduleBasicPack;
import com.company.campaign.builder.capability.schedule.ScheduleBasicVersions;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.FrozenAuthoringPolicy;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.builder.proposal.ProposalBaseRevisions;
import com.company.campaign.builder.proposal.ProposalClientContext;
import com.company.campaign.builder.proposal.ProposalMode;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.security.context.TrustedExecutionContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Deterministic T27 fixtures built only from accepted public contracts and immutable host inputs. No clock,
 * network, database, model or Provider is touched; the read-only catalogs and the accepted capability packs are
 * the only frozen inputs.
 */
public final class CampaignAssemblyFixtures {

    public static final String CONVERSATION = "conversation-t27";
    public static final String RUN = "run-t27";
    public static final String CLIENT_REF = "message-new";
    public static final String PROMOTION_CLIENT_REF = "promotion-new";
    public static final String SCHEDULE_CLIENT_REF = "campaign-schedule";
    public static final String GOVERNANCE_CLIENT_REF = "campaign-governance";

    public static final String TEMPLATE_REFERENCE = "tpl-welcome";
    public static final String REWARD_REFERENCE = "rwd-points";

    private CampaignAssemblyFixtures() {
    }

    // ------------------------------------------------------------------ immutable catalogs

    /** A Message Template catalog with EMAIL, WECHAT and SMS templates plus one disabled entry. */
    public static MessageTemplateCatalog messageCatalog() {
        return MessageTemplateCatalog.of("tpl-catalog-1", List.of(
                MessageTemplateVersion.of(TEMPLATE_REFERENCE, "tpl-welcome", "tpl-welcome-v1", "EMAIL",
                        List.of(new MessageTemplateVariable("firstName", CapabilityValueKind.STRING, true, 16)),
                        true, true, MessageClassification.PUBLIC),
                MessageTemplateVersion.of("tpl-wechat-notification", "tpl-wechat-notification",
                        "tpl-wechat-notification-v1", "WECHAT",
                        List.of(new MessageTemplateVariable("bodyText", CapabilityValueKind.STRING, true, 4096)),
                        true, true, MessageClassification.PUBLIC),
                MessageTemplateVersion.of("tpl-sms-notification", "tpl-sms-notification",
                        "tpl-sms-notification-v1", "SMS",
                        List.of(new MessageTemplateVariable("bodyText", CapabilityValueKind.STRING, true, 4096)),
                        true, true, MessageClassification.PUBLIC),
                MessageTemplateVersion.of("tpl-disabled", "tpl-disabled", "tpl-disabled-v1", "IN_APP",
                        List.of(), false, true, MessageClassification.INTERNAL)));
    }

    /** A Reward catalog with one enabled, runtime-available version. */
    public static RewardCatalog rewardCatalog() {
        return RewardCatalog.of("rwd-catalog-1", List.of(
                RewardVersion.of(REWARD_REFERENCE, "rwd-points", "rwd-points-v1", "POINTS", "CNY",
                        new BigDecimal("10.0000"), new BigDecimal("100.0000"), true, true)));
    }

    public static MessageTemplatePack messagePack() {
        return new MessageTemplatePack(messageCatalog());
    }

    public static PromotionReferencePack promotionPack() {
        return new PromotionReferencePack(rewardCatalog());
    }

    public static FrozenCapabilityCatalog capabilityCatalog() {
        return BuilderFixtures.catalog(messagePack(), promotionPack());
    }

    public static FrozenAuthoringPolicy policy() {
        return BuilderFixtures.policy();
    }

    public static TrustedExecutionContext context() {
        return BuilderFixtures.context();
    }

    /** The complete host-owned run evidence over the frozen catalogs and accepted packs. */
    public static CampaignToolRunContext runContext() {
        MessageTemplateCatalog messages = messageCatalog();
        RewardCatalog rewards = rewardCatalog();
        MessageTemplatePack messagePack = new MessageTemplatePack(messages);
        PromotionReferencePack promotionPack = new PromotionReferencePack(rewards);
        ScheduleBasicPack schedulePack = new ScheduleBasicPack();
        GovernanceLimitPack governancePack = new GovernanceLimitPack();
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(messagePack, promotionPack);
        FrozenAuthoringPolicy policy = BuilderFixtures.policy();
        TrustedExecutionContext context = BuilderFixtures.context();
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
                return schedulePack;
            }

            @Override
            public GovernanceLimitPack governancePack() {
                return governancePack;
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
        };
    }

    // ------------------------------------------------------------------ accepted Schedule / Governance declarations

    /** The accepted {@code schedule.basic} declaration of the one accepted DAILY form. */
    public static CapabilityPayload scheduleDeclaration() {
        return CapabilityPayload.of(Map.of(
                ScheduleBasicVersions.FIELD_FORM, ScheduleBasicForm.DAILY.name(),
                ScheduleBasicVersions.FIELD_ZONE_ID, "Asia/Shanghai",
                ScheduleBasicVersions.FIELD_LOCAL_TIME, "09:00",
                ScheduleBasicVersions.FIELD_START_DATE, "2026-11-01",
                ScheduleBasicVersions.FIELD_END_DATE, "2026-11-30"));
    }

    /** The accepted {@code governance.limit} declaration of the one accepted bounded limit set. */
    public static CapabilityPayload governanceDeclaration() {
        return CapabilityPayload.of(Map.of(
                GovernanceLimitVersions.FIELD_MAX_RECIPIENTS, 1_000,
                GovernanceLimitVersions.FIELD_MAX_REWARD_GRANTS, 100,
                GovernanceLimitVersions.FIELD_MAX_REWARD_FACE_VALUE, new BigDecimal("25.00"),
                GovernanceLimitVersions.FIELD_CURRENCY, "CNY"));
    }

    // ------------------------------------------------------------------ typed calls

    public static CampaignToolCall call(String callId, CampaignToolRequest request) {
        return call(ContractVersions.CURRENT, callId, request.toolId(), "1.0", request);
    }

    public static CampaignToolCall call(
            SchemaVersion schemaVersion, String callId, String toolId, String toolVersion,
            CampaignToolRequest request) {
        return new CampaignToolCall(schemaVersion, callId, toolId, toolVersion, request);
    }

    /** A call naming an arbitrary identifier and version, used to prove the boundary refuses before a handler. */
    public static CampaignToolCall call(
            String callId, String toolId, String toolVersion, CampaignToolRequest request) {
        return new CampaignToolCall(ContractVersions.CURRENT, callId, toolId, toolVersion, request);
    }

    public static CapabilityPayload variables(String name, Object value) {
        return CapabilityPayload.of(Map.of(name, value));
    }

    // ------------------------------------------------------------------ assembly context

    public static CampaignAssemblyContext createContext() {
        return new CampaignAssemblyContext(
                CONVERSATION, RUN, ProposalMode.CREATE, null, baseRevisions(null), ProposalClientContext.EMPTY,
                CampaignToolCatalog.standard());
    }

    public static CampaignAssemblyContext editContext(long campaignRevision) {
        return new CampaignAssemblyContext(
                CONVERSATION, RUN, ProposalMode.EDIT, BuilderFixtures.CAMPAIGN, baseRevisions(campaignRevision),
                ProposalClientContext.EMPTY, CampaignToolCatalog.standard());
    }

    public static ProposalBaseRevisions baseRevisions(Long campaignRevision) {
        MessageTemplatePack messagePack = messagePack();
        PromotionReferencePack promotionPack = promotionPack();
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(messagePack, promotionPack);
        FrozenAuthoringPolicy policy = BuilderFixtures.policy();
        return new ProposalBaseRevisions(
                campaignRevision, catalog.catalogVersion(), catalog.catalogHash(),
                policy.policyVersion(), policy.policyHash());
    }

    // ------------------------------------------------------------------ accepted admissions

    /** Runs the ordered Message Skill flow and returns the final accepted safe-preview admission. */
    public static CampaignToolAdmission messagePreviewAdmission() {
        CampaignToolInvoker invoker = messageInvoker();
        invoker.invoke(call("call-1", new CampaignToolRequest.MessageDiscover(null, 64)));
        invoker.invoke(call("call-2", new CampaignToolRequest.MessageResolve(TEMPLATE_REFERENCE)));
        invoker.invoke(call("call-3", new CampaignToolRequest.MessageVariablesValidate(
                TEMPLATE_REFERENCE, variables("firstName", "Ada"))));
        return invoker.invoke(call("call-4", new CampaignToolRequest.MessagePreviewSafe(
                TEMPLATE_REFERENCE, variables("firstName", "Ada"))));
    }

    /** Runs the ordered Promotion Skill flow and returns the final accepted safe-preview admission. */
    public static CampaignToolAdmission rewardPreviewAdmission() {
        CampaignToolInvoker invoker = rewardInvoker();
        invoker.invoke(call("call-1", new CampaignToolRequest.RewardDiscover(null, 64)));
        invoker.invoke(call("call-2", new CampaignToolRequest.RewardResolve(REWARD_REFERENCE)));
        return invoker.invoke(call("call-3", new CampaignToolRequest.RewardPreviewSafe(
                REWARD_REFERENCE, 3, new BigDecimal("100.0000"))));
    }

    public static CampaignToolInvoker messageInvoker() {
        return new CampaignToolInvoker(CampaignToolCatalog.standard(), CampaignSkillIds.MESSAGE_TEMPLATE,
                StandardCampaignToolHandlers.all(), runContext());
    }

    public static CampaignToolInvoker rewardInvoker() {
        return new CampaignToolInvoker(CampaignToolCatalog.standard(), CampaignSkillIds.PROMOTION_REFERENCE,
                StandardCampaignToolHandlers.all(), runContext());
    }

    public static CampaignArtifactAdapter adapter() {
        return new CampaignArtifactAdapter(CONVERSATION, RUN, CampaignToolCatalog.standard());
    }

    /** Runs the ordered Schedule Skill flow and returns the final accepted validation admission. */
    public static CampaignToolAdmission scheduleValidationAdmission() {
        CampaignToolInvoker invoker = scheduleInvoker();
        invoker.invoke(call("call-1", new CampaignToolRequest.ScheduleNormalize(
                SCHEDULE_CLIENT_REF, scheduleDeclaration())));
        return invoker.invoke(call("call-2", new CampaignToolRequest.ScheduleValidate(
                SCHEDULE_CLIENT_REF, scheduleDeclaration())));
    }

    /** Runs the ordered Governance Skill flow and returns the final accepted validation admission. */
    public static CampaignToolAdmission governanceValidationAdmission() {
        CampaignToolInvoker invoker = governanceInvoker();
        invoker.invoke(call("call-1", new CampaignToolRequest.GovernanceExposureCalculate(
                GOVERNANCE_CLIENT_REF, governanceDeclaration())));
        return invoker.invoke(call("call-2", new CampaignToolRequest.GovernanceValidate(
                GOVERNANCE_CLIENT_REF, governanceDeclaration())));
    }

    public static CampaignToolInvoker scheduleInvoker() {
        return new CampaignToolInvoker(CampaignToolCatalog.standard(), CampaignSkillIds.SCHEDULE_BASIC,
                StandardCampaignToolHandlers.all(), runContext());
    }

    public static CampaignToolInvoker governanceInvoker() {
        return new CampaignToolInvoker(CampaignToolCatalog.standard(), CampaignSkillIds.GOVERNANCE_LIMIT,
                StandardCampaignToolHandlers.all(), runContext());
    }
}