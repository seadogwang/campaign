package com.company.campaign.builder.authoring.testsupport;

import com.company.campaign.builder.agent.SegmentPreviewCard;
import com.company.campaign.builder.authoring.SegmentAuthoringInputs;
import com.company.campaign.builder.authoring.SegmentAuthoringVersions;
import com.company.campaign.builder.authoring.SegmentRuleBasedCapability;
import com.company.campaign.builder.compiler.CapabilityCompileContext;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CapabilityReadiness;
import com.company.campaign.builder.compiler.CapabilityRequest;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.preview.PreviewConditionStatistic;
import com.company.campaign.query.plan.SegmentQueryPlanner;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.segment.validation.SegmentValidationPolicy;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.segment.validation.SegmentValidator;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic T16 fixtures built only from accepted T02/T04/T05/T07/T08/T09/T11 public contracts.
 *
 * <p>No clock, network, database, model, provider or random identifier is touched. The document, snapshot,
 * trusted context, validation result, semantic plan, Preview card and envelope are all derived from the
 * genuine accepted boundaries, so a test that mutates one pin is mutating a real artifact.
 */
public final class SegmentAuthoringFixtures {

    public static final String LOGICAL_ID = "segment-0001";
    public static final String CLIENT_REF = "segment-client-ref-1";

    public static final ContentHash SKILL_HASH =
            ContentHash.sha256("authoring-skill-1".getBytes(StandardCharsets.UTF_8));
    public static final ContentHash PREVIEW_HASH =
            ContentHash.sha256("authoring-preview-1".getBytes(StandardCharsets.UTF_8));
    public static final ContentHash GATE_INPUTS_HASH =
            ContentHash.sha256("authoring-gate-inputs-1".getBytes(StandardCharsets.UTF_8));
    public static final ContentHash APPROVED_DECISION_HASH =
            ContentHash.sha256("authoring-approved-decision-1".getBytes(StandardCharsets.UTF_8));

    private static final SegmentDslCodec DSL_CODEC = new SegmentDslCodec();

    private SegmentAuthoringFixtures() {
    }

    // ------------------------------------------------------------------ accepted artifacts

    public static SegmentDsl document() {
        return SegmentValidationFixtures.validDocument();
    }

    public static MetadataSnapshot snapshot() {
        return SegmentValidationFixtures.snapshot();
    }

    public static TrustedExecutionContext context() {
        return SegmentValidationFixtures.context();
    }

    /** The same trusted principal under a foreign tenant, to prove snapshot ownership is re-proved. */
    public static TrustedExecutionContext foreignOwnerContext() {
        return SegmentValidationFixtures.foreignOwnerContext();
    }

    /** The accepted predicate re-bound to a different Metadata Snapshot identifier. */
    public static SegmentDsl documentBoundTo(String snapshotId) {
        return SegmentValidationFixtures.publish(
                SegmentValidationFixtures.binding(snapshotId), document().predicate());
    }

    /** The canonical base64 wire of one document. */
    public static String wireOf(SegmentDsl document) {
        return Base64.getEncoder().encodeToString(DSL_CODEC.write(document));
    }

    public static SegmentValidator validator() {
        return SegmentValidationFixtures.validator();
    }

    public static SegmentValidationPolicy validationPolicy() {
        return SegmentValidationFixtures.policy();
    }

    public static SegmentQueryPlanner planner() {
        return new SegmentQueryPlanner(SegmentValidationFixtures.allowAll());
    }

    public static SegmentRuleBasedCapability contract() {
        return new SegmentRuleBasedCapability(validator(), validationPolicy(), planner());
    }

    public static SegmentValidationResult validation() {
        return validator().validate(document(), snapshot(), context(), validationPolicy());
    }

    public static SemanticQueryPlan plan() {
        return planner().plan(validation(), snapshot(), context());
    }

    /** The exact canonical base64 wire of the accepted document. */
    public static String dslWire() {
        return Base64.getEncoder().encodeToString(DSL_CODEC.write(document()));
    }

    // ------------------------------------------------------------------ safe Preview card

    public static SegmentPreviewCard card() {
        return cardBuilder().build();
    }

    public static CardBuilder cardBuilder() {
        return new CardBuilder();
    }

    /** A fluent builder over the server-owned safe Preview card, so a test can mutate exactly one pin. */
    public static final class CardBuilder {

        private int matchedCount = 42;
        private List<PreviewConditionStatistic> statistics =
                List.of(new PreviewConditionStatistic("r.0", 3, 1, 0));
        private List<String> sampleTokens = List.of(ContentHash.sha256("sample-1".getBytes(
                StandardCharsets.UTF_8)).value());
        private String metadataSnapshotId = snapshot().snapshotId();
        private String asOf = context().snapshotRef().asOf().toString();
        private String timezone = context().snapshotRef().timezone().getId();
        private String dataWatermark = context().snapshotRef().dataWatermark().toString();
        private ContentHash previewHash = PREVIEW_HASH;
        private ContentHash planHash = plan().planHash();
        private ContentHash dslHash = document().editableDslHash();
        private ContentHash canonicalAstHash = validation().canonicalAstHash();
        private ContentHash metadataHash = snapshot().contentHash();
        private ContentHash skillHash = SKILL_HASH;

        public CardBuilder matchedCount(int value) {
            this.matchedCount = value;
            return this;
        }

        public CardBuilder statistics(List<PreviewConditionStatistic> value) {
            this.statistics = value;
            return this;
        }

        public CardBuilder sampleTokens(List<String> value) {
            this.sampleTokens = value;
            return this;
        }

        public CardBuilder metadataSnapshotId(String value) {
            this.metadataSnapshotId = value;
            return this;
        }

        public CardBuilder asOf(String value) {
            this.asOf = value;
            return this;
        }

        public CardBuilder timezone(String value) {
            this.timezone = value;
            return this;
        }

        public CardBuilder dataWatermark(String value) {
            this.dataWatermark = value;
            return this;
        }

        public CardBuilder previewHash(ContentHash value) {
            this.previewHash = value;
            return this;
        }

        public CardBuilder planHash(ContentHash value) {
            this.planHash = value;
            return this;
        }

        public CardBuilder dslHash(ContentHash value) {
            this.dslHash = value;
            return this;
        }

        public CardBuilder canonicalAstHash(ContentHash value) {
            this.canonicalAstHash = value;
            return this;
        }

        public CardBuilder metadataHash(ContentHash value) {
            this.metadataHash = value;
            return this;
        }

        public CardBuilder skillHash(ContentHash value) {
            this.skillHash = value;
            return this;
        }

        public SegmentPreviewCard build() {
            return new SegmentPreviewCard("SUCCESS", matchedCount, metadataSnapshotId, asOf, timezone,
                    dataWatermark, statistics, sampleTokens, previewHash, planHash, dslHash, canonicalAstHash,
                    metadataHash, skillHash);
        }
    }

    // ------------------------------------------------------------------ envelope

    public static Map<String, Object> envelope() {
        return envelopeBuilder().build();
    }

    public static EnvelopeBuilder envelopeBuilder() {
        return new EnvelopeBuilder();
    }

    /** A fluent builder over the closed capability payload envelope. */
    public static final class EnvelopeBuilder {

        private String version = SegmentAuthoringVersions.ENVELOPE_FORMAT_VERSION;
        private String dslWire = SegmentAuthoringFixtures.dslWire();
        private String canonicalAstHash = validation().canonicalAstHash().value();
        private String semanticPlanHash = plan().planHash().value();
        private String previewHash = PREVIEW_HASH.value();
        private String skillHash = SKILL_HASH.value();
        private final Map<String, Object> extra = new LinkedHashMap<>();
        private final List<String> removed = new ArrayList<>();

        public EnvelopeBuilder version(String value) {
            this.version = value;
            return this;
        }

        public EnvelopeBuilder dslWire(String value) {
            this.dslWire = value;
            return this;
        }

        public EnvelopeBuilder canonicalAstHash(String value) {
            this.canonicalAstHash = value;
            return this;
        }

        public EnvelopeBuilder semanticPlanHash(String value) {
            this.semanticPlanHash = value;
            return this;
        }

        public EnvelopeBuilder previewHash(String value) {
            this.previewHash = value;
            return this;
        }

        public EnvelopeBuilder skillHash(String value) {
            this.skillHash = value;
            return this;
        }

        /** Adds one extra, non-envelope key, to prove an unknown field is refused. */
        public EnvelopeBuilder extra(String key, Object value) {
            this.extra.put(key, value);
            return this;
        }

        /** Removes one known key, to prove a missing field is refused. */
        public EnvelopeBuilder without(String key) {
            this.removed.add(key);
            return this;
        }

        public Map<String, Object> build() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("envelopeVersion", version);
            fields.put("dslWire", dslWire);
            fields.put("canonicalAstHash", canonicalAstHash);
            fields.put("semanticPlanHash", semanticPlanHash);
            fields.put("previewHash", previewHash);
            fields.put("skillHash", skillHash);
            fields.putAll(extra);
            for (String key : removed) {
                fields.remove(key);
            }
            return fields;
        }
    }

    // ------------------------------------------------------------------ frozen inputs and readiness

    public static SegmentAuthoringInputs inputs() {
        return inputs(snapshot(), card(), SKILL_HASH);
    }

    public static SegmentAuthoringInputs inputs(
            MetadataSnapshot snapshot, SegmentPreviewCard card, ContentHash skillHash) {
        return new SegmentAuthoringInputs(snapshot, card, skillHash);
    }

    public static CapabilityReadiness experimental() {
        return new CapabilityReadiness(
                SegmentAuthoringVersions.CAPABILITY_CODE,
                CapabilityReadiness.EXPERIMENTAL,
                false,
                List.of("APPROVAL_MISSING"),
                null,
                GATE_INPUTS_HASH);
    }

    public static CapabilityReadiness active() {
        return new CapabilityReadiness(
                SegmentAuthoringVersions.CAPABILITY_CODE,
                CapabilityReadiness.ACTIVE,
                true,
                List.of(),
                APPROVED_DECISION_HASH,
                GATE_INPUTS_HASH);
    }

    public static CapabilityCompileContext compileContext() {
        return compileContext(experimental(), inputs());
    }

    public static CapabilityCompileContext compileContext(
            CapabilityReadiness readiness, SegmentAuthoringInputs inputs) {
        return new CapabilityCompileContext(List.of(readiness), List.of(inputs));
    }

    public static CapabilityCompileContext withoutReadiness() {
        return new CapabilityCompileContext(List.of(), List.of(inputs()));
    }

    public static CapabilityCompileContext withoutInputs() {
        return new CapabilityCompileContext(List.of(experimental()), List.of());
    }

    // ------------------------------------------------------------------ requests

    public static CapabilityRequest request() {
        return request(envelope());
    }

    public static CapabilityRequest request(Map<String, Object> payload) {
        return request(payload, context(), compileContext());
    }

    public static CapabilityRequest request(
            Map<String, Object> payload, TrustedExecutionContext context, CapabilityCompileContext compileContext) {
        SegmentRuleBasedCapability contract = contract();
        return new CapabilityRequest(
                LOGICAL_ID,
                CapabilityPayload.of(payload),
                BuilderFixtures.catalog(contract),
                BuilderFixtures.policy(),
                context,
                compileContext);
    }
}