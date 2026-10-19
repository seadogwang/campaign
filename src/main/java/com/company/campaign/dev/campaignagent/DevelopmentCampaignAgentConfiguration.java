package com.company.campaign.dev.campaignagent;

import com.company.campaign.builder.campaignagent.CampaignAgentEnvironment;
import com.company.campaign.builder.campaignagent.CampaignBusinessResourceTool;
import com.company.campaign.builder.campaignagent.CampaignCompileEvidenceRegistry;
import com.company.campaign.builder.campaignagent.CampaignSchemaRepairPolicy;
import com.company.campaign.builder.campaignagent.http.CampaignAgentConversationController;
import com.company.campaign.builder.campaignagent.http.CampaignAgentConversationHistoryController;
import com.company.campaign.builder.campaignagent.http.CampaignAgentConversationHistoryService;
import com.company.campaign.builder.campaignagent.http.CampaignAgentConversationService;
import com.company.campaign.builder.campaignagent.http.CampaignAgentTurnController;
import com.company.campaign.builder.campaignagent.http.CampaignAgentTurnService;
import com.company.campaign.builder.campaignagent.http.CampaignPlanCompileService;
import com.company.campaign.builder.campaignagent.http.CampaignPlanHttpController;
import com.company.campaign.builder.compiler.CampaignCompiler;
import com.company.campaign.builder.compiler.CampaignPlanStore;
import com.company.campaign.builder.compiler.FrozenAuthoringPolicy;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.persistence.PostgresCampaignPlanStore;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.builder.segment.SegmentSchemaRepairPolicy;
import com.company.campaign.builder.tool.SegmentPreviewPort;
import com.company.campaign.campaignagent.CampaignConversationHistoryService;
import com.company.campaign.conversation.CampaignConversationAuditSink;
import com.company.campaign.conversation.CampaignConversationHistoryStore;
import com.company.campaign.conversation.CampaignConversationIdentitySource;
import com.company.campaign.conversation.CampaignConversationStore;
import com.company.campaign.conversation.MyBatisPlusCampaignBusinessResourceTool;
import com.company.campaign.conversation.MyBatisPlusCampaignConversationStore;
import com.company.campaign.conversation.postgres.ConversationPostgresProperties;
import com.company.campaign.dev.preview.postgres.PreviewPostgresConfiguration;
import com.company.campaign.dev.segment.DevelopmentAnthropicModelPort;
import com.company.campaign.dev.segment.DevelopmentModelConfiguration;
import com.company.campaign.dev.segment.DevelopmentStructuredModelPort;
import com.company.campaign.model.StructuredCampaignRequirementExtractor;
import com.company.campaign.persistence.CampaignDatasourceProperties;
import com.company.campaign.persistence.CampaignDatasourceConfiguration;
import com.company.campaign.security.context.CampaignPermission;
import jakarta.annotation.PostConstruct;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The focused opt-in configuration of the local Campaign Agent development slice.
 *
 * <p>It is inert unless the host sets {@code campaign.agent.development.enabled=true}. When it is off — the
 * default — the application creates no {@link CampaignAgentTurnService}, no Campaign Agent route mapping, no
 * conversation registry and no model client at all, so default startup stays exactly as inert as before.
 *
 * <p>When it is on it wires, fail-closed and in one place: the shared Anthropic-compatible structured transport,
 * the bounded routing model and typed Skill input resolvers over that transport, the accepted Tool catalog,
 * Capability Packs and Segment integration, the one Campaign conversation store and audit sink, the clearly
 * named fixed development identity provider and the concrete turn service, and it publishes the strict
 * controller as an explicit bean.
 *
 * <p>The conversation store is selected by an explicit, validated mode. The default {@code IN_MEMORY} keeps the
 * development-only in-memory registry and creates no DataSource, no pool and no migration run; an explicit
 * {@code campaign.agent.development.store.mode=POSTGRESQL} uses the durable MyBatis-Plus store over the shared
 * business {@link SqlSessionFactory} from {@code CampaignDatasourceConfiguration}. A host-supplied
 * {@link CampaignConversationStore} bean always wins, and more than one fails startup as an ambiguous wiring.
 *
 * <p>The Segment source is resolved with explicit precedence: a host-supplied
 * {@link DevelopmentSegmentSourceProvider} always wins; otherwise exactly one host {@link SegmentPreviewPort}
 * makes this build the real development Segment provider over the same model object as the Campaign structured
 * transport; with no Preview port the honest inert provider stays. Since T34C the opt-in PostgreSQL Preview
 * integration can supply that one port itself (default off, under
 * {@code campaign.agent.development.preview.postgres.enabled}), so an audience request can resolve end to end
 * without a host-written Preview adapter — while a host-supplied port keeps documented precedence and an
 * ambiguous host-plus-auto wiring still aborts startup. An ambiguous or incompatible wiring aborts startup
 * with one bounded configuration code rather than silently selecting a second model or a fake Preview.
 *
 * <p>A missing or malformed model configuration aborts the application start with one bounded code and a
 * message that carries no credential, URL or raw value. This configuration is explicitly
 * {@code DEVELOPMENT_ONLY}: it delivers no production identity, no production audit persistence and no
 * production model evidence, the durable store it can select is unverified against a real PostgreSQL instance in
 * this repository, and the genuine PostgreSQL Preview path is local development integration rather than
 * production Preview evidence.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        DevelopmentCampaignAgentProperties.class, ConversationPostgresProperties.class,
        CampaignDatasourceProperties.class})
@Import(CampaignDatasourceConfiguration.class)
@ConditionalOnProperty(prefix = "campaign.agent.development", name = "enabled", havingValue = "true")
public class DevelopmentCampaignAgentConfiguration {

    private final DevelopmentCampaignAgentProperties properties;
    private final ConversationPostgresProperties storeProperties;
    private final CampaignDatasourceProperties campaignDatasourceProperties;

    public DevelopmentCampaignAgentConfiguration(
            DevelopmentCampaignAgentProperties properties,
            ConversationPostgresProperties storeProperties,
            CampaignDatasourceProperties campaignDatasourceProperties) {
        this.properties = properties;
        this.storeProperties = storeProperties;
        this.campaignDatasourceProperties = campaignDatasourceProperties;
    }

    @PostConstruct
    void validateLegacyConfiguration() {
        properties.validate();
        storeProperties.inheritFrom(campaignDatasourceProperties);
        storeProperties.validate();
        if (storeProperties.isPostgresql()) {
            campaignDatasourceProperties.validateForLegacy(
                    storeProperties.getUrl(), storeProperties.getUsername(),
                    storeProperties.getPassword(), storeProperties.getSchema());
        }
    }

    /** The one development clock; every development timestamp is read from it, never from {@code Instant.now()}. */
    @Bean
    public Clock campaignAgentDevelopmentClock() {
        return Clock.systemUTC();
    }

    /** The clearly named fixed development identity provider. */
    @Bean
    public DevelopmentCampaignAgentIdentity campaignAgentDevelopmentIdentity(Clock campaignAgentDevelopmentClock) {
        return new DevelopmentCampaignAgentIdentity(
                campaignAgentDevelopmentClock, Set.of(CampaignPermission.values()));
    }

    /** The bean name of the one store this configuration publishes. */
    public static final String STORE_BEAN_NAME = "campaignAgentDevelopmentStore";

    /**
     * The one Campaign conversation store of the opt-in host, resolved fail-closed and in one place.
     *
     * <p>Precedence is explicit. A host-supplied {@link CampaignConversationStore} bean always wins, and more
     * than one fails startup as an ambiguous wiring. With none supplied, the validated store mode decides:
     * {@code IN_MEMORY} (the default) keeps the development-only in-memory registry and touches no database at
     * all, while an explicit {@code POSTGRESQL} publishes the durable MyBatis-Plus store over the shared
     * business {@link SqlSessionFactory}.
     *
     * <p>This bean is {@link Primary}, so it is the one a host dependency resolves by type even when the host
     * supplied its own store: the object it returns is that host store, so no injection point can accidentally
     * see a second, differently configured store.
     */
    @Bean(name = STORE_BEAN_NAME)
    @Primary
    public CampaignConversationStore campaignAgentDevelopmentStore(
            Clock campaignAgentDevelopmentClock,
            ListableBeanFactory beanFactory,
            @Qualifier(CampaignDatasourceProperties.SQL_SESSION_FACTORY_BEAN_NAME)
            ObjectProvider<SqlSessionFactory> sessionFactoryProviders) {
        properties.validate();
        storeProperties.validate();
        // A host-supplied store has explicit precedence. This bean is excluded by name rather than by type, so
        // the lookup can never resolve the bean being defined and can never be ambiguous about which one it saw.
        List<String> hostNames = new ArrayList<>();
        for (String name : beanFactory.getBeanNamesForType(CampaignConversationStore.class, true, true)) {
            if (!STORE_BEAN_NAME.equals(name)) {
                hostNames.add(name);
            }
        }
        if (hostNames.size() > 1) {
            throw invalidHostPort("conversationStore");
        }
        if (hostNames.size() == 1) {
            return beanFactory.getBean(hostNames.get(0), CampaignConversationStore.class);
        }
        if (storeProperties.isPostgresql()) {
            SqlSessionFactory sessionFactory = sessionFactoryProviders.getIfAvailable();
            if (sessionFactory == null) {
                throw new DevelopmentCampaignAgentConfigurationException(
                        DevelopmentCampaignAgentErrorCodes.CONFIGURATION_INVALID,
                        "The PostgreSQL Campaign conversation store requires the shared business "
                                + "SqlSessionFactory.");
            }
            return new MyBatisPlusCampaignConversationStore(
                    sessionFactory,
                    campaignAgentDevelopmentClock,
                    properties.getMaxConversations(),
                    properties.getMaxOutcomesPerConversation(),
                    properties.getRetention());
        }
        return new InMemoryDevelopmentConversationStore(
                campaignAgentDevelopmentClock,
                properties.getMaxConversations(),
                properties.getMaxOutcomesPerConversation(),
                properties.getRetention());
    }

    /**
     * The one development-only conversation transcript of the opt-in host.
     *
     * <p>It is inert — it holds no directory and writes no file — unless the host explicitly set
     * {@code campaign.agent.development.transcript.enabled=true} and named
     * {@code campaign.agent.development.transcript.directory}. Because this bean exists only inside this
     * development configuration, the transcript is structurally excluded from the production Campaign host, and
     * it never weakens the accepted production audit redaction contract.
     *
     * <p>When it is enabled the directory is created and resolved to its real path here, so an absent, unusable
     * or symbolic-link directory fails startup closed with one bounded, value-free code instead of the host
     * silently recording nothing while claiming to record.
     */
    @Bean
    public DevelopmentTranscriptRecorder campaignAgentDevelopmentTranscriptRecorder(
            Clock campaignAgentDevelopmentClock) {
        return DevelopmentTranscriptRecorder.of(
                properties.getTranscript(), campaignAgentDevelopmentClock);
    }

    /** The development host environment over the shared transport and the frozen development assets. */
    @Bean
    public CampaignAgentEnvironment campaignAgentDevelopmentEnvironment(
            ObjectProvider<DevelopmentStructuredModelPort> transports,
            ObjectProvider<DevelopmentSegmentSourceProvider> segmentSourceProviders,
            ObjectProvider<CampaignBusinessResourceTool> businessResourceTools,
            @Qualifier(CampaignDatasourceProperties.SQL_SESSION_FACTORY_BEAN_NAME)
            ObjectProvider<SqlSessionFactory> sessionFactoryProviders,
            ListableBeanFactory beanFactory,
            DevelopmentCampaignAgentIdentity identity,
            DevelopmentTranscriptRecorder transcript,
            Clock campaignAgentDevelopmentClock,
            CampaignCompileEvidenceRegistry compileEvidenceRegistry) {
        DevelopmentStructuredModelPort transport = resolveTransport(transports);
        DevelopmentSegmentSourceProvider segmentSourceProvider = resolveSegmentSource(
                segmentSourceProviders, beanFactory, transport, identity, campaignAgentDevelopmentClock,
                transcript, properties.isSchemaRepairEnabled());
        CampaignSchemaRepairPolicy repairPolicy = properties.isSchemaRepairEnabled()
                ? CampaignSchemaRepairPolicy.ONE_REPAIR
                : CampaignSchemaRepairPolicy.NONE;
        CampaignBusinessResourceTool businessResourceTool = resolveBusinessResourceTool(
                businessResourceTools, storeProperties, sessionFactoryProviders);
        // The observation decorators are pure pass-throughs: they return exactly the delegate's bytes, so the
        // frozen routing, Skill-resolution, Segment and Tool evidence stay byte-identical whether or not the
        // transcript is enabled. They are deliberately not beans, so no host port is published twice.
        DevelopmentTranscriptSkillTransport observedTransport =
                new DevelopmentTranscriptSkillTransport(transport, transcript);
        return new DevelopmentCampaignAgentEnvironment(
                new DevelopmentTranscriptRoutingModel(new DevelopmentCampaignRoutingModel(transport), transcript),
                repairPolicy,
                DevelopmentCampaignAgentAssets.toolRunContext(identity.context()),
                DevelopmentCampaignAgentAssets.segmentIntegration(),
                segmentSourceProvider,
                new DevelopmentSkillInputResolvers(observedTransport),
                new StructuredCampaignRequirementExtractor(
                        observedTransport, campaignAgentDevelopmentClock,
                        () -> identity.executionReference().timezone()),
                campaignAgentDevelopmentClock,
                identity,
                businessResourceTool,
                compileEvidenceRegistry);
    }

    private static CampaignBusinessResourceTool resolveBusinessResourceTool(
            ObjectProvider<CampaignBusinessResourceTool> providers,
            ConversationPostgresProperties storeProperties,
            ObjectProvider<SqlSessionFactory> sessionFactoryProviders) {
        List<CampaignBusinessResourceTool> supplied = providers.orderedStream().limit(2).toList();
        if (supplied.size() > 1) {
            throw invalidHostPort("businessResourceTool");
        }
        if (!supplied.isEmpty()) {
            return supplied.getFirst();
        }
        if (!storeProperties.isPostgresql()) {
            return null;
        }
        SqlSessionFactory sessionFactory = sessionFactoryProviders.getIfAvailable();
        if (sessionFactory == null) {
            throw new DevelopmentCampaignAgentConfigurationException(
                    DevelopmentCampaignAgentErrorCodes.CONFIGURATION_INVALID,
                    "The PostgreSQL Campaign business-resource Tool requires the shared business "
                            + "SqlSessionFactory.");
        }
        return new MyBatisPlusCampaignBusinessResourceTool(sessionFactory);
    }

    private static DevelopmentStructuredModelPort resolveTransport(
            ObjectProvider<DevelopmentStructuredModelPort> transports) {
        List<DevelopmentStructuredModelPort> supplied = transports.orderedStream().limit(2).toList();
        if (supplied.size() > 1) {
            throw invalidHostPort("structuredModelPort");
        }
        if (supplied.size() == 1) {
            return supplied.getFirst();
        }
        try {
            return new DevelopmentAnthropicModelPort(DevelopmentModelConfiguration.load());
        } catch (RuntimeException failure) {
            throw new DevelopmentCampaignAgentConfigurationException(
                    DevelopmentCampaignAgentErrorCodes.MODEL_CONFIGURATION_UNAVAILABLE,
                    "The development Campaign Agent model configuration is unavailable.", failure);
        }
    }

    /**
     * The one Segment source provider of the opt-in host, resolved fail-closed and in one place.
     *
     * <p>Precedence is explicit. A host-supplied {@link DevelopmentSegmentSourceProvider} always wins. For the
     * Preview port itself: exactly one host {@link SegmentPreviewPort} bean has precedence over the honest inert
     * provider, and the explicit development PostgreSQL Preview integration may supply exactly one port of its
     * own when the host supplies none. Two host ports, or a host port together with the auto-published one, are
     * an ambiguous wiring and fail startup with one bounded code rather than depending on bean order. Only when
     * a Preview port resolves does this build the real development provider — over the same selected model
     * object the Campaign host uses as its structured transport, so a single model never become two. With no
     * Preview port the honest inert provider stays, because a real Segment cannot be previewed without one and
     * this build never registers a fake default.
     */
    private static DevelopmentSegmentSourceProvider resolveSegmentSource(
            ObjectProvider<DevelopmentSegmentSourceProvider> providers,
            ListableBeanFactory beanFactory,
            DevelopmentStructuredModelPort transport,
            DevelopmentCampaignAgentIdentity identity,
            Clock clock,
            DevelopmentTranscriptRecorder transcript,
            boolean schemaRepairEnabled) {
        List<DevelopmentSegmentSourceProvider> suppliedProviders = providers.orderedStream().limit(2).toList();
        if (suppliedProviders.size() > 1) {
            throw invalidHostPort("segmentSourceProvider");
        }

        // The auto-published development Preview port is identified by its explicit bean name, so a host port is
        // never confused with it and the resolution never depends on registration order.
        boolean autoPreviewPort = false;
        List<SegmentPreviewPort> hostPreviewPorts = new ArrayList<>();
        for (String name : beanFactory.getBeanNamesForType(SegmentPreviewPort.class, true, false)) {
            if (PreviewPostgresConfiguration.PREVIEW_PORT_BEAN_NAME.equals(name)) {
                autoPreviewPort = true;
            } else {
                hostPreviewPorts.add(beanFactory.getBean(name, SegmentPreviewPort.class));
            }
        }
        if (hostPreviewPorts.size() > 1) {
            throw invalidHostPort("segmentPreviewPort");
        }
        if (autoPreviewPort && hostPreviewPorts.size() == 1) {
            // A host port and the auto-published development Preview port are an ambiguous wiring, so the start
            // fails closed instead of silently selecting one of two differently configured Preview boundaries.
            throw invalidHostPort("segmentPreviewPort");
        }
        if (!suppliedProviders.isEmpty()) {
            // An explicit host source always wins, even when a Preview port also exists: the host already said
            // which source it wants, so no Preview port is silently substituted for it.
            return suppliedProviders.getFirst();
        }
        SegmentPreviewPort previewPort = hostPreviewPorts.isEmpty()
                ? (autoPreviewPort
                        ? beanFactory.getBean(PreviewPostgresConfiguration.PREVIEW_PORT_BEAN_NAME,
                                SegmentPreviewPort.class)
                        : null)
                : hostPreviewPorts.getFirst();
        if (previewPort == null) {
            return DevelopmentSegmentSourceProvider.none();
        }
        if (!(transport instanceof SegmentIntentModelPort intentModel)) {
            // Selecting a second model would silently run the Segment Agent against a different model than the
            // Campaign host, so an incompatible transport fails startup with one bounded code instead.
            throw new DevelopmentCampaignAgentConfigurationException(
                    DevelopmentCampaignAgentErrorCodes.SEGMENT_MODEL_TRANSPORT_INCOMPATIBLE,
                    "The selected development structured transport cannot also serve as the Segment intent "
                            + "model, so the real Segment source cannot be built from it.");
        }
        // The observation decorator is a pure pass-through over the same selected model object, so the genuine
        // Segment Agent sees byte-identical Intent bytes whether or not the transcript is enabled.
        return DevelopmentSegmentAgentSourceProvider.of(
                new DevelopmentTranscriptSegmentModel(intentModel, transcript), previewPort, clock, identity,
                schemaRepairEnabled
                        ? SegmentSchemaRepairPolicy.ONE_SCHEMA_REPAIR
                        : SegmentSchemaRepairPolicy.DISABLED);
    }

    private static DevelopmentCampaignAgentConfigurationException invalidHostPort(String port) {
        return new DevelopmentCampaignAgentConfigurationException(
                DevelopmentCampaignAgentErrorCodes.CONFIGURATION_INVALID,
                "The opt-in Campaign Agent development host requires at most one " + port + '.');
    }

    /** The development-only in-memory audit sink. It is not production audit persistence. */
    @Bean
    public CampaignConversationAuditSink campaignAgentDevelopmentAuditSink() {
        return new InMemoryCampaignConversationAuditSink();
    }

    /**
     * The cryptographically strong, server-owned conversation identity source of the opt-in local host.
     *
     * <p>It is the only source that mints a conversation identity in main wiring: no caller selects one, and
     * neither component embeds a raw user, tenant or program value.
     */
    @Bean
    public CampaignConversationIdentitySource campaignAgentDevelopmentIdentitySource() {
        return new SecureCampaignConversationIdentitySource();
    }

    /**
     * The concrete conversation-create service, wired from the explicit host dependencies.
     *
     * <p>The strict controller is registered explicitly below, so this bean and its route exist only inside
     * this opt-in configuration.
     */
    @Bean
    public CampaignAgentConversationService campaignAgentDevelopmentConversationService(
            DevelopmentCampaignAgentIdentity identity,
            CampaignConversationStore store,
            CampaignConversationIdentitySource identitySource,
            DevelopmentTranscriptRecorder transcript) {
        return new DevelopmentCampaignAgentConversationService(identity, store, identitySource, transcript);
    }

    /** The strict conversation-create HTTP adapter, registered only while this opt-in configuration is active. */
    @Bean
    public CampaignAgentConversationController campaignAgentConversationController(
            CampaignAgentConversationService service) {
        return new CampaignAgentConversationController(service);
    }

    /**
     * The concrete turn service, wired from the explicit host dependencies.
     *
     * <p>The strict controller is registered explicitly below, so this bean and its route exist only inside
     * this opt-in configuration.
     */
    @Bean
    public CampaignAgentTurnService campaignAgentDevelopmentTurnService(
            DevelopmentCampaignAgentIdentity identity,
            CampaignAgentEnvironment environment,
            CampaignConversationStore store,
            CampaignConversationAuditSink auditSink,
            DevelopmentTranscriptRecorder transcript,
            Clock campaignAgentDevelopmentClock) {
        return new DevelopmentCampaignAgentTurnService(
                identity, environment, store,
                DevelopmentCampaignAgentAssets.assemblyContextFactory(), auditSink,
                campaignAgentDevelopmentClock, transcript);
    }

    /** The strict HTTP adapter, registered only while this opt-in configuration is active. */
    @Bean
    public CampaignAgentTurnController campaignAgentTurnController(CampaignAgentTurnService service) {
        return new CampaignAgentTurnController(service);
    }

    /**
     * The read-only conversation-history service of the opt-in local host.
     *
     * <p>It is published only when the selected store can genuinely enumerate its own conversations, so a
     * host-supplied store that cannot serve history fails the start closed rather than rendering an empty list
     * from an invented fallback.
     */
    @Bean
    public CampaignAgentConversationHistoryService campaignAgentDevelopmentConversationHistoryService(
            DevelopmentCampaignAgentIdentity identity,
            CampaignAgentEnvironment environment,
            CampaignConversationStore store) {
        if (!(store instanceof CampaignConversationHistoryStore historyStore)) {
            throw new DevelopmentCampaignAgentConfigurationException(
                    DevelopmentCampaignAgentErrorCodes.CONFIGURATION_INVALID,
                    "The selected Campaign conversation store cannot serve conversation history.");
        }
        return new CampaignConversationHistoryService(identity, historyStore, environment);
    }

    /** The read-only conversation-history HTTP adapter, registered only in this opt-in host. */
    @Bean
    public CampaignAgentConversationHistoryController campaignAgentConversationHistoryController(
            CampaignAgentConversationHistoryService service) {
        return new CampaignAgentConversationHistoryController(service);
    }

    // ------------------------------------------------------------------ compile evidence registry

    /**
     * The bounded, development-only in-memory compile-evidence registry.
     *
     * <p>It bridges the conversation flow and the plan compile service: the conversation
     * stages host-owned Segment compile evidence by conversationId+runId when a Segment
     * Ready result is accepted, then binds it to the final proposal hash during finalization.
     * The compile service looks it up by proposal hash.
     */
    @Bean
    public CampaignCompileEvidenceRegistry campaignAgentDevelopmentCompileEvidenceRegistry(
            Clock campaignAgentDevelopmentClock) {
        return new DevelopmentCampaignCompileEvidenceRegistry(campaignAgentDevelopmentClock);
    }

    // ------------------------------------------------------------------ plan compile

    /**
     * The one Campaign Plan store of the opt-in host, fail-closed.
     *
     * <p>The plan-compile route is only registered when PostgreSQL mode is explicitly enabled
     * ({@code campaign.agent.development.store.mode=POSTGRESQL}) <strong>and</strong> the shared
     * business {@link SqlSessionFactory} is available. There is no in-memory fallback: a host
     * that has not opted into PostgreSQL mode fails startup rather than exposing a false accepted
     * plan-compile route. The plan-compile controller and service beans are conditioned on this
     * bean's existence, so they are not registered into the container at all outside PostgreSQL
     * mode.
     */
    @Bean
    @ConditionalOnProperty(prefix = "campaign.agent.development.store", name = "mode",
            havingValue = "POSTGRESQL", matchIfMissing = false)
    public CampaignPlanStore campaignAgentDevelopmentPlanStore(
            @Qualifier(CampaignDatasourceProperties.SQL_SESSION_FACTORY_BEAN_NAME)
            ObjectProvider<SqlSessionFactory> sessionFactoryProviders) {
        SqlSessionFactory sessionFactory = sessionFactoryProviders.getIfAvailable();
        if (sessionFactory == null) {
            throw new DevelopmentCampaignAgentConfigurationException(
                    DevelopmentCampaignAgentErrorCodes.CONFIGURATION_INVALID,
                    "The PostgreSQL Campaign Plan store requires the shared business "
                            + "SqlSessionFactory.");
        }
        return new PostgresCampaignPlanStore(sessionFactory);
    }

    /** The frozen capability catalog of the plan-compile controller. */
    @Bean
    public FrozenCapabilityCatalog campaignAgentDevelopmentCompileCatalog() {
        return DevelopmentCampaignAgentAssets.capabilityCatalog();
    }

    /** The frozen authoring policy of the plan-compile controller. */
    @Bean
    public FrozenAuthoringPolicy campaignAgentDevelopmentCompilePolicy() {
        return DevelopmentCampaignAgentAssets.authoringPolicy();
    }

    /** The Campaign compiler over the development authorization boundary. */
    @Bean
    public CampaignCompiler campaignAgentDevelopmentCompiler() {
        return new CampaignCompiler(
                com.company.campaign.dev.segment.CoreLoyaltyDevelopmentProfile.authorizationService());
    }

    /** The development plan-compile service, published only when the durable plan store is active. */
    @Bean
    @ConditionalOnBean(CampaignPlanStore.class)
    public CampaignPlanCompileService campaignAgentDevelopmentPlanCompileService(
            CampaignCompiler compiler,
            CampaignPlanStore planStore,
            DevelopmentCampaignAgentIdentity identity,
            FrozenCapabilityCatalog catalog,
            FrozenAuthoringPolicy policy,
            Clock campaignAgentDevelopmentClock,
            CampaignCompileEvidenceRegistry compileEvidenceRegistry) {
        return new DevelopmentCampaignPlanCompileService(
                compiler, planStore, identity, catalog, policy, campaignAgentDevelopmentClock,
                compileEvidenceRegistry);
    }

    /** The strict plan-compile HTTP adapter, registered only while the plan-compile service is active. */
    @Bean
    @ConditionalOnBean(CampaignPlanCompileService.class)
    public CampaignPlanHttpController campaignPlanHttpController(CampaignPlanCompileService service) {
        return new CampaignPlanHttpController(service);
    }
}
