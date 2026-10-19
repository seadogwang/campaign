package com.company.campaign.campaignagent;

import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.assembly.CampaignToolCatalog;
import com.company.campaign.builder.assembly.CampaignToolRunContext;
import com.company.campaign.builder.authoring.SegmentAuthoringIntegration;
import com.company.campaign.builder.campaignagent.CampaignAgentEnvironment;
import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.campaignagent.CampaignAgentException;
import com.company.campaign.builder.campaignagent.CampaignAgentSession;
import com.company.campaign.builder.campaignagent.CampaignAgentTurnRequest;
import com.company.campaign.builder.campaignagent.CampaignAgentTurnResult;
import com.company.campaign.builder.campaignagent.CampaignBusinessResourceCatalog;
import com.company.campaign.builder.campaignagent.CampaignClarificationAnswer;
import com.company.campaign.builder.campaignagent.CampaignCompileEvidenceRegistry;
import com.company.campaign.builder.campaignagent.CampaignConversationAgent;
import com.company.campaign.builder.campaignagent.CampaignConversationProjection;
import com.company.campaign.builder.campaignagent.CampaignGovernanceInputs;
import com.company.campaign.builder.campaignagent.CampaignJourneyInputs;
import com.company.campaign.builder.campaignagent.CampaignMessageInputs;
import com.company.campaign.builder.campaignagent.CampaignPromotionInputs;
import com.company.campaign.builder.campaignagent.CampaignRequirementExtractorPort;
import com.company.campaign.builder.campaignagent.CampaignRouteKind;
import com.company.campaign.builder.campaignagent.CampaignRoutingModelPort;
import com.company.campaign.builder.campaignagent.CampaignSchemaRepairPolicy;
import com.company.campaign.builder.campaignagent.CampaignScheduleInputs;
import com.company.campaign.builder.campaignagent.CampaignSegmentContinuation;
import com.company.campaign.builder.campaignagent.CampaignSegmentSubmission;
import com.company.campaign.builder.campaignagent.CampaignTurnTime;
import com.company.campaign.builder.campaignagent.http.CampaignAgentHttpErrorCodes;
import com.company.campaign.builder.campaignagent.http.CampaignAgentTurnCodec;
import com.company.campaign.builder.campaignagent.http.CampaignAgentTurnHttpRequest;
import com.company.campaign.builder.campaignagent.http.CampaignAgentTurnHttpResponse;
import com.company.campaign.builder.campaignagent.http.CampaignAgentTurnService;
import com.company.campaign.builder.proposal.ProposalIdentity;
import com.company.campaign.conversation.CampaignConversationAuditRecord;
import com.company.campaign.conversation.CampaignConversationAuditSink;
import com.company.campaign.conversation.CampaignConversationKey;
import com.company.campaign.conversation.CampaignConversationStore;
import com.company.campaign.conversation.CampaignConversationTransaction;
import com.company.campaign.conversation.CampaignStoredOutcome;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.TrustedContextProvider;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The neutral concrete {@link CampaignAgentTurnService} of this build.
 *
 * <p>Its constructor requires explicit host dependencies only — a trusted-context provider, the Campaign
 * environment, the conversation store, the assembly-context factory, an audit sink and an injected clock. There
 * is no thread-local, no global security holder and no identity read from the request body: the trusted context
 * is resolved and validated on every call, and the conversation is bound to the trusted tenant, program and
 * user that created it.
 *
 * <p>Every call requires an active membership and {@code campaign.edit}, and the Segment/audience Tool path is
 * refused before any model or Tool invocation when the identity lacks {@code audience.preview}. A denial, an
 * absent conversation on a later revision and a cross-boundary call all return the same bounded not-found or
 * forbidden response and never disclose whether another tenant owns a conversation.
 *
 * <p>A turn is atomic per conversation key. A repeated call with the same idempotency key and the same request
 * fingerprint returns the stored, byte-equivalent response without another model or Tool call; the same key with
 * a different request is a bounded conflict. A failed turn stores neither a session nor an idempotency outcome.
 *
 * <p>It lives outside every {@code dev} package, so the production Campaign host runs the same turn semantics
 * over the production identity boundary, the durable store and the durable audit sink; the development host
 * keeps its clearly named subclass.
 */
public class CampaignConversationTurnService implements CampaignAgentTurnService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 96;
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,95}$");
    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final TrustedContextProvider trustedContextProvider;
    private final CampaignAgentEnvironment environment;
    private final CampaignConversationStore store;
    private final CampaignAssemblyContextFactory assemblyContextFactory;
    private final CampaignConversationAuditSink auditSink;
    private final Clock clock;
    private final CampaignAgentTurnCodec codec = new CampaignAgentTurnCodec();

    public CampaignConversationTurnService(
            TrustedContextProvider trustedContextProvider,
            CampaignAgentEnvironment environment,
            CampaignConversationStore store,
            CampaignAssemblyContextFactory assemblyContextFactory,
            CampaignConversationAuditSink auditSink,
            Clock clock) {
        this.trustedContextProvider = Objects.requireNonNull(trustedContextProvider, "trustedContextProvider");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.store = Objects.requireNonNull(store, "store");
        this.assemblyContextFactory = Objects.requireNonNull(assemblyContextFactory, "assemblyContextFactory");
        this.auditSink = auditSink == null ? CampaignConversationAuditSink.none() : auditSink;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CampaignAgentTurnHttpResponse turn(CampaignAgentTurnHttpRequest request, String idempotencyKey) {
        Objects.requireNonNull(request, "request");
        requireIdempotencyKey(idempotencyKey);
        TrustedExecutionContext context = resolveContext();
        requirePermission(context, CampaignPermission.CAMPAIGN_EDIT);
        CampaignConversationKey key = new CampaignConversationKey(
                context.tenantId(), context.programId(), context.userId(),
                request.conversationId(), request.runId());
        ContentHash fingerprint = ContentHash.sha256(fingerprintMaterial(request));

        CampaignAgentTurnHttpResponse response;
        CampaignAgentTurnResult result;
        try (CampaignConversationTransaction transaction = store.open(key)) {
            CampaignStoredOutcome stored = transaction.stored(idempotencyKey);
            if (stored != null) {
                if (!stored.requestFingerprint().equals(fingerprint)) {
                    throw CampaignAgentException.of(
                            CampaignAgentHttpErrorCodes.CONFLICT,
                            "The idempotency key was already used for a different request.");
                }
                return codec.response(stored.result());
            }
            CampaignAgentSession session = transaction.session();
            if (session == null) {
                // A turn never creates a conversation: only the server-issued conversation route reserves one.
                // An unreserved, absent or cross-boundary conversation is the same bounded absence here.
                if (!transaction.reserved() || request.expectedRevision() != 0L) {
                    throw CampaignAgentException.of(
                            CampaignAgentHttpErrorCodes.NOT_FOUND,
                            "No conversation exists at this trusted boundary.");
                }
                session = new CampaignAgentSession(assemblyContextFactory.create(key, context));
            }
            requireAudiencePermission(session, context);
            result = new CampaignConversationAgent(gatedEnvironment(context))
                    .handle(session, new CampaignAgentTurnRequest(
                            request.expectedRevision(), request.message(), request.answers(), request.at()));
            CampaignStoredOutcome outcome = new CampaignStoredOutcome(fingerprint, result);
            // A refused turn stores no session: nothing partial can ever become the stored state.
            transaction.commit(result.accepted() ? session : null, idempotencyKey, outcome);
            response = codec.response(result);
        }
        recordAudit(key, context, result);
        return response;
    }

    // ------------------------------------------------------------------ trusted boundary

    private TrustedExecutionContext resolveContext() {
        TrustedExecutionContext context;
        try {
            context = trustedContextProvider.trustedContext();
        } catch (CampaignAgentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw CampaignAgentException.of(
                    CampaignAgentHttpErrorCodes.UNAUTHENTICATED, "No trusted identity could be resolved.");
        }
        if (context == null || !context.membership().isActive()) {
            throw CampaignAgentException.of(
                    CampaignAgentHttpErrorCodes.UNAUTHENTICATED, "No active trusted identity could be resolved.");
        }
        return context;
    }

    private static void requirePermission(TrustedExecutionContext context, CampaignPermission permission) {
        if (!context.hasPermission(permission)) {
            throw CampaignAgentException.of(
                    CampaignAgentHttpErrorCodes.FORBIDDEN,
                    "The trusted identity does not hold the permission this turn requires.");
        }
    }

    /**
     * Refuses an audience-context turn before any model or Tool invocation when {@code audience.preview} is
     * absent.
     *
     * <p>The host knows when a conversation is already in an audience flow — it produced an audience result, or
     * a Segment component is staged — so it denies the turn up front rather than running a model or a Tool the
     * identity may not use. For every other turn the gated environment simply makes the Segment Skill
     * unavailable, so no Segment Tool path can run either.
     */
    private static void requireAudiencePermission(CampaignAgentSession session, TrustedExecutionContext context) {
        if (context.hasPermission(CampaignPermission.AUDIENCE_PREVIEW)) {
            return;
        }
        boolean audienceContext = session.lastRouteKind() == CampaignRouteKind.AUDIENCE_REQUEST
                || session.workspace().inspection().components().stream()
                        .anyMatch(component -> CampaignSkillIds.SEGMENT_RULE_BASED.equals(component.skillId()));
        if (audienceContext) {
            throw CampaignAgentException.of(
                    CampaignAgentHttpErrorCodes.FORBIDDEN,
                    "Audience selection requires the audience.preview permission.");
        }
    }

    /** A per-call view of the environment in which the Segment Skill is gated by the trusted permission. */
    private CampaignAgentEnvironment gatedEnvironment(TrustedExecutionContext context) {
        return context.hasPermission(CampaignPermission.AUDIENCE_PREVIEW)
                ? environment
                : new PermissionGatedEnvironment(environment);
    }

    // ------------------------------------------------------------------ idempotency and audit

    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            throw CampaignAgentException.of(
                    CampaignAgentHttpErrorCodes.IDEMPOTENCY_KEY_REQUIRED,
                    "Every Campaign conversation turn requires a bounded Idempotency-Key.");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH
                || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw CampaignAgentException.of(
                    CampaignAgentHttpErrorCodes.IDEMPOTENCY_KEY_INVALID,
                    "The Idempotency-Key is malformed or over its declared bound.");
        }
    }

    private static byte[] fingerprintMaterial(CampaignAgentTurnHttpRequest request) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("conversationId", request.conversationId());
        material.put("runId", request.runId());
        material.put("expectedRevision", request.expectedRevision());
        material.put("message", request.message());
        List<Map<String, Object>> answers = new ArrayList<>();
        for (CampaignClarificationAnswer answer : request.answers()) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("code", answer.code());
            encoded.put("optionId", answer.optionId());
            encoded.put("freeText", answer.freeText());
            answers.add(encoded);
        }
        material.put("answers", answers);
        material.put("at", request.at());
        return CANONICAL_JSON.write(material);
    }

    private void recordAudit(
            CampaignConversationKey key, TrustedExecutionContext context, CampaignAgentTurnResult result) {
        CampaignConversationProjection projection = result.projection();
        try {
            auditSink.record(new CampaignConversationAuditRecord(
                    key.fingerprint(),
                    context.tenantId(),
                    context.programId(),
                    context.userId(),
                    projection.routeKind() == null ? null : projection.routeKind().name(),
                    projection.phase().name(),
                    result.accepted(),
                    result.code(),
                    projection.revision(),
                    clock.instant()));
        } catch (RuntimeException failure) {
            // A bounded audit surface must never turn a committed turn into a reported failure.
        }
    }

    // ------------------------------------------------------------------ gated environment

    /**
     * The environment with the Segment Skill removed, for an identity without {@code audience.preview}.
     *
     * <p>It is a defensive decorator: every non-Segment accessor is delegated unchanged, and only the accepted
     * Segment integration and the host Segment source become {@code null}, which makes the Segment Skill report
     * a bounded {@code Unavailable} instead of running a Segment Tool.
     */
    private static final class PermissionGatedEnvironment implements CampaignAgentEnvironment {

        private final CampaignAgentEnvironment delegate;

        private PermissionGatedEnvironment(CampaignAgentEnvironment delegate) {
            this.delegate = delegate;
        }

        @Override
        public CampaignRoutingModelPort routingModel() {
            return delegate.routingModel();
        }

        @Override
        public CampaignTurnTime trustedTime() {
            return delegate.trustedTime();
        }

        @Override
        public CampaignRequirementExtractorPort requirementExtractor() {
            return delegate.requirementExtractor();
        }

        @Override
        public CampaignBusinessResourceCatalog businessResourceCatalog() {
            return delegate.businessResourceCatalog();
        }

        @Override
        public CampaignSchemaRepairPolicy repairPolicy() {
            return delegate.repairPolicy();
        }

        @Override
        public CampaignToolCatalog toolCatalog() {
            return delegate.toolCatalog();
        }

        @Override
        public CampaignToolRunContext toolRunContext() {
            return delegate.toolRunContext();
        }

        @Override
        public SegmentAuthoringIntegration segmentIntegration() {
            return null;
        }

        @Override
        public CampaignSegmentSubmission segmentSource(String conversationId, String runId, String utterance) {
            return null;
        }

        @Override
        public CampaignSegmentSubmission segmentSource(
                String conversationId,
                String runId,
                String utterance,
                CampaignSegmentContinuation continuation) {
            return null;
        }

        @Override
        public CampaignMessageInputs messageInputs(
                String utterance, List<CampaignClarificationAnswer> answers) {
            return delegate.messageInputs(utterance, answers);
        }

        @Override
        public CampaignPromotionInputs promotionInputs(
                String utterance, List<CampaignClarificationAnswer> answers) {
            return delegate.promotionInputs(utterance, answers);
        }

        @Override
        public CampaignScheduleInputs scheduleInputs(
                String utterance, List<CampaignClarificationAnswer> answers) {
            return delegate.scheduleInputs(utterance, answers);
        }

        @Override
        public CampaignGovernanceInputs governanceInputs(
                String utterance, List<CampaignClarificationAnswer> answers) {
            return delegate.governanceInputs(utterance, answers);
        }

        @Override
        public CampaignJourneyInputs journeyInputs(
                String utterance, List<CampaignClarificationAnswer> answers) {
            return delegate.journeyInputs(utterance, answers);
        }

        @Override
        public ProposalIdentity targetFor(String skillId) {
            return delegate.targetFor(skillId);
        }

        @Override
        public CampaignCompileEvidenceRegistry compileEvidenceRegistry() {
            return delegate.compileEvidenceRegistry();
        }

        @Override
        public T19PackIndex journeyPackIndex() {
            return delegate.journeyPackIndex();
        }
    }

    /** A bounded diagnostic: the wiring facts only, never a value. */
    @Override
    public String toString() {
        return "CampaignConversationTurnService[serverOwnedIdentity=true]";
    }
}
