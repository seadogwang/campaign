package com.company.campaign.segment.intent.testsupport;

import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.SourceSpan;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.intent.IntentAmbiguity;
import com.company.campaign.segment.intent.IntentClause;
import com.company.campaign.segment.intent.IntentProvenance;
import com.company.campaign.segment.intent.IntentValue;
import com.company.campaign.segment.intent.LogicalExpression;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.intent.UnsupportedRequirement;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.List;

/**
 * Deterministic T10 fixtures. No clock, random identifier, network or database is used.
 */
public final class IntentFixtures {

    /** The original utterance the standard spans are grounded in. */
    public static final String UTTERANCE = "Gold会员且过去90天消费超过1000元";

    /** A span covering the whole utterance. */
    public static final SourceSpan FULL_SPAN = new SourceSpan(UTTERANCE, 0, UTTERANCE.length());

    /** An utterance that names a spend condition and an existing audience by natural language. */
    public static final String REFERENCE_UTTERANCE = "过去90天消费超过1000元且属于高价值客户人群";

    /** The natural-language audience phrase inside {@link #REFERENCE_UTTERANCE}. */
    public static final String AUDIENCE_PHRASE = "高价值客户人群";

    /** The zero-based start of {@link #AUDIENCE_PHRASE} inside {@link #REFERENCE_UTTERANCE}. */
    public static final int AUDIENCE_START = 17;

    /** An utterance whose audience phrase uses dynamic "最新" wording instead of a name. */
    public static final String DYNAMIC_REFERENCE_UTTERANCE = "过去90天消费超过1000元且属于最新人群";

    /** The dynamic audience phrase inside {@link #DYNAMIC_REFERENCE_UTTERANCE}. */
    public static final String DYNAMIC_AUDIENCE_PHRASE = "最新人群";

    /** The zero-based start of {@link #DYNAMIC_AUDIENCE_PHRASE} inside its utterance. */
    public static final int DYNAMIC_AUDIENCE_START = 17;

    /** An utterance whose audience phrase combines dynamic wording with the audience's own name. */
    public static final String DYNAMIC_NAMED_UTTERANCE = "过去90天消费超过1000元且属于最新VIP高价值人群";

    /** The dynamic-but-nameable phrase inside {@link #DYNAMIC_NAMED_UTTERANCE}. */
    public static final String DYNAMIC_NAMED_PHRASE = "最新VIP高价值人群";

    /** The zero-based start of {@link #DYNAMIC_NAMED_PHRASE} inside its utterance. */
    public static final int DYNAMIC_NAMED_START = 17;

    private IntentFixtures() {
    }

    // ------------------------------------------------------------------ audience references

    /** One first-class audience reference grounded in an exact span of the supplied text. */
    public static com.company.campaign.segment.intent.IntentAudienceReference audienceReference(
            String referenceId, List<String> candidates, IntentProvenance provenance) {
        return new com.company.campaign.segment.intent.IntentAudienceReference(
                referenceId, candidates, provenance);
    }

    /** One audience reference grounded in the exact audience phrase of the standard reference utterance. */
    public static com.company.campaign.segment.intent.IntentAudienceReference audienceReference(
            String referenceId, String candidateText) {
        return audienceReference(
                referenceId,
                List.of(candidateText),
                span(AUDIENCE_PHRASE, AUDIENCE_START, AUDIENCE_START + AUDIENCE_PHRASE.length()));
    }

    /** One audience reference grounded in one exact span of the supplied text. */
    public static com.company.campaign.segment.intent.IntentAudienceReference audienceReference(
            String referenceId, String candidateText, String spanText, int start) {
        return audienceReference(
                referenceId, List.of(candidateText), span(spanText, start, start + spanText.length()));
    }

    /** The standard reference Intent with its one reference replaced by the supplied one. */
    public static SegmentIntent referenceIntentWithReference(
            com.company.campaign.segment.intent.IntentAudienceReference reference) {
        SegmentIntent base = referenceIntent();
        LogicalExpression logical = new LogicalExpression.Group(
                com.company.campaign.segment.intent.LogicalOperator.ALL,
                List.of(new LogicalExpression.Clause(base.clauses().get(0).clauseId()),
                        new LogicalExpression.Clause(reference.referenceId())));
        return new SegmentIntent(
                ContractVersions.CURRENT, SubjectKind.MEMBER, base.clauses(), logical, List.of(), List.of(),
                List.of(), List.of(), List.of(reference));
    }

    /** The one standard reference request: {@code ref-1} naming {@link #AUDIENCE_PHRASE}. */
    public static com.company.campaign.segment.intent.IntentAudienceReference standardAudienceReference() {
        return audienceReference("ref-1", AUDIENCE_PHRASE);
    }

    /** A spend clause plus one audience reference, composed by {@code ALL}. */
    public static SegmentIntent referenceIntent() {
        IntentClause spend = new IntentClause(
                "c1", "SPEND", MetadataKind.METRIC, false, ComparisonOperator.GREATER_THAN,
                new IntentValue.MoneyValue("1000"),
                new com.company.campaign.segment.intent.IntentWindow.Rolling(90,
                        com.company.campaign.segment.intent.IntentWindowUnit.DAY),
                new com.company.campaign.segment.intent.IntentCurrency.Explicit("CNY"),
                com.company.campaign.metadata.domain.AggregateFunction.SUM, null,
                span("过去90天消费超过1000元", 0, 14), List.of(), List.of());
        com.company.campaign.segment.intent.IntentAudienceReference reference = standardAudienceReference();
        LogicalExpression logical = new LogicalExpression.Group(
                com.company.campaign.segment.intent.LogicalOperator.ALL,
                List.of(new LogicalExpression.Clause(spend.clauseId()),
                        new LogicalExpression.Clause(reference.referenceId())));
        return new SegmentIntent(
                ContractVersions.CURRENT, SubjectKind.MEMBER, List.of(spend), logical, List.of(), List.of(),
                List.of(), List.of(), List.of(reference));
    }

    /** An Intent that is only one audience reference, with no clause at all. */
    public static SegmentIntent referenceOnlyIntent() {
        com.company.campaign.segment.intent.IntentAudienceReference reference = standardAudienceReference();
        return new SegmentIntent(
                ContractVersions.CURRENT, SubjectKind.MEMBER, List.of(),
                new LogicalExpression.Clause(reference.referenceId()), List.of(), List.of(),
                List.of(), List.of(), List.of(reference));
    }

    /** The standard reference Intent rendered in strict JSON, grounded in {@link #REFERENCE_UTTERANCE}. */
    public static String referenceIntentJson() {
        return "{"
                + "\"intentVersion\":\"1.3\","
                + "\"subject\":\"MEMBER\","
                + "\"clauses\":["
                + "{\"clauseId\":\"c1\",\"concept\":\"SPEND\",\"definitionKind\":\"METRIC\","
                + "\"negated\":false,\"operator\":\"GREATER_THAN\",\"value\":{\"kind\":\"MONEY\",\"raw\":\"1000\"},"
                + "\"window\":{\"kind\":\"ROLLING\",\"amount\":90,\"unit\":\"DAY\"},"
                + "\"currency\":{\"mode\":\"EXPLICIT\",\"code\":\"CNY\"},\"aggregation\":\"SUM\","
                + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"过去90天消费超过1000元\",\"start\":0,\"end\":14},"
                + "\"candidateHints\":[],\"assumptions\":[]}"
                + "],"
                + "\"logicalExpression\":{\"type\":\"ALL\",\"children\":["
                + "{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},{\"type\":\"CLAUSE\",\"clauseId\":\"ref-1\"}]},"
                + "\"ambiguities\":[],\"unsupportedRequirements\":[],"
                + "\"audienceReferences\":["
                + "{\"referenceId\":\"ref-1\",\"candidateTexts\":[\"" + AUDIENCE_PHRASE + "\"],"
                + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"" + AUDIENCE_PHRASE + "\",\"start\":"
                + AUDIENCE_START + ",\"end\":" + (AUDIENCE_START + AUDIENCE_PHRASE.length()) + "}}"
                + "]"
                + "}";
    }

    public static IntentProvenance span(String text, int start, int end) {
        return new IntentProvenance.FromSourceSpan(new SourceSpan(text, start, end));
    }

    public static IntentClause clause(String clauseId, String concept, SourceSpan span) {
        return clause(clauseId, concept, new IntentProvenance.FromSourceSpan(span));
    }

    public static IntentClause clause(String clauseId, String concept, IntentProvenance provenance) {
        return new IntentClause(
                clauseId, concept, null, false, null, null, null, null, null, null, provenance,
                List.of(), List.of());
    }

    /** A clause with definition kind, operator and value populated. */
    public static IntentClause clause(
            String clauseId,
            String concept,
            MetadataKind kind,
            ComparisonOperator operator,
            IntentValue value,
            IntentProvenance provenance) {
        return new IntentClause(
                clauseId, concept, kind, false, operator, value, null, null, null, null, provenance,
                List.of(), List.of());
    }

    /** Rebuilds a clause with a different concept machine code. */
    public static IntentClause withConcept(IntentClause clause, String concept) {
        return new IntentClause(
                clause.clauseId(), concept, clause.definitionKind(), clause.negated(),
                clause.operator(), clause.value(), clause.window(), clause.currency(), clause.aggregation(),
                clause.relationScope(), clause.provenance(), clause.candidateHints(), clause.assumptions());
    }

    /** Rebuilds a clause with extra candidate hints. */
    public static IntentClause withHints(IntentClause clause, List<String> hints) {
        return new IntentClause(
                clause.clauseId(), clause.concept(), clause.definitionKind(), clause.negated(),
                clause.operator(), clause.value(), clause.window(), clause.currency(), clause.aggregation(),
                clause.relationScope(), clause.provenance(), hints, clause.assumptions());
    }

    /** Rebuilds a clause with a different window slot. */
    public static IntentClause withWindow(IntentClause clause, com.company.campaign.segment.intent.IntentWindow window) {
        return new IntentClause(
                clause.clauseId(), clause.concept(), clause.definitionKind(), clause.negated(),
                clause.operator(), clause.value(), window, clause.currency(), clause.aggregation(),
                clause.relationScope(), clause.provenance(), clause.candidateHints(), clause.assumptions());
    }

    /** Rebuilds a clause with a different currency slot. */
    public static IntentClause withCurrency(
            IntentClause clause, com.company.campaign.segment.intent.IntentCurrency currency) {
        return new IntentClause(
                clause.clauseId(), clause.concept(), clause.definitionKind(), clause.negated(),
                clause.operator(), clause.value(), clause.window(), currency, clause.aggregation(),
                clause.relationScope(), clause.provenance(), clause.candidateHints(), clause.assumptions());
    }

    /** Rebuilds a clause with a different aggregation slot. */
    public static IntentClause withAggregation(
            IntentClause clause, com.company.campaign.metadata.domain.AggregateFunction aggregation) {
        return new IntentClause(
                clause.clauseId(), clause.concept(), clause.definitionKind(), clause.negated(),
                clause.operator(), clause.value(), clause.window(), clause.currency(), aggregation,
                clause.relationScope(), clause.provenance(), clause.candidateHints(), clause.assumptions());
    }

    /** Rebuilds a clause with a different relation-scope slot. */
    public static IntentClause withRelationScope(
            IntentClause clause, com.company.campaign.metadata.domain.RelationScope scope) {
        return new IntentClause(
                clause.clauseId(), clause.concept(), clause.definitionKind(), clause.negated(),
                clause.operator(), clause.value(), clause.window(), clause.currency(), clause.aggregation(),
                scope, clause.provenance(), clause.candidateHints(), clause.assumptions());
    }

    /** Rebuilds a clause with a different operator. */
    public static IntentClause withOperator(IntentClause clause, ComparisonOperator operator) {
        return new IntentClause(
                clause.clauseId(), clause.concept(), clause.definitionKind(), clause.negated(),
                operator, clause.value(), clause.window(), clause.currency(), clause.aggregation(),
                clause.relationScope(), clause.provenance(), clause.candidateHints(), clause.assumptions());
    }

    /** Rebuilds a clause with a different value. */
    public static IntentClause withValue(IntentClause clause, IntentValue value) {
        return new IntentClause(
                clause.clauseId(), clause.concept(), clause.definitionKind(), clause.negated(),
                clause.operator(), value, clause.window(), clause.currency(), clause.aggregation(),
                clause.relationScope(), clause.provenance(), clause.candidateHints(), clause.assumptions());
    }

    public static SegmentIntent intent(IntentClause clause) {
        return intent(List.of(clause), LogicalExpression.Clause::new, List.of(), List.of());
    }

    public static SegmentIntent intent(
            List<IntentClause> clauses,
            java.util.function.Function<String, LogicalExpression> expression,
            List<IntentAmbiguity> ambiguities,
            List<UnsupportedRequirement> unsupported) {
        String first = clauses.get(0).clauseId();
        LogicalExpression logical = clauses.size() == 1
                ? expression.apply(first)
                : new LogicalExpression.Group(
                        com.company.campaign.segment.intent.LogicalOperator.ALL,
                        clauses.stream().map(c -> (LogicalExpression) new LogicalExpression.Clause(c.clauseId())).toList());
        return new SegmentIntent(
                ContractVersions.CURRENT, SubjectKind.MEMBER, clauses, logical, ambiguities, unsupported);
    }

    /** A valid Intent whose two clauses carry exact source spans in {@link #UTTERANCE}. */
    public static SegmentIntent standardIntent() {
        IntentClause tier = new IntentClause(
                "c1", "MEMBER_TIER", MetadataKind.ATTRIBUTE, false, ComparisonOperator.EQUAL,
                new IntentValue.EnumValue("Gold"), null, null, null, null,
                span("Gold会员", 0, 6), List.of(), List.of());
        IntentClause spend = new IntentClause(
                "c2", "SPEND", MetadataKind.METRIC, false, ComparisonOperator.GREATER_THAN,
                new IntentValue.MoneyValue("1000"),
                new com.company.campaign.segment.intent.IntentWindow.Rolling(90,
                        com.company.campaign.segment.intent.IntentWindowUnit.DAY),
                new com.company.campaign.segment.intent.IntentCurrency.Explicit("CNY"),
                com.company.campaign.metadata.domain.AggregateFunction.SUM, null,
                span("过去90天消费超过1000元", 7, 21), List.of(), List.of());
        return intent(List.of(tier, spend), null, List.of(), List.of());
    }

    /** The standard Intent rendered in strict JSON, grounded in {@link #UTTERANCE}. */
    public static String standardIntentJson() {
        return "{"
                + "\"intentVersion\":\"1.3\","
                + "\"subject\":\"MEMBER\","
                + "\"clauses\":["
                + "{\"clauseId\":\"c1\",\"concept\":\"MEMBER_TIER\",\"definitionKind\":\"ATTRIBUTE\","
                + "\"negated\":false,\"operator\":\"EQUAL\",\"value\":{\"kind\":\"ENUM\",\"raw\":\"Gold\"},"
                + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"Gold会员\",\"start\":0,\"end\":6},"
                + "\"candidateHints\":[],\"assumptions\":[]},"
                + "{\"clauseId\":\"c2\",\"concept\":\"SPEND\",\"definitionKind\":\"METRIC\","
                + "\"negated\":false,\"operator\":\"GREATER_THAN\",\"value\":{\"kind\":\"MONEY\",\"raw\":\"1000\"},"
                + "\"window\":{\"kind\":\"ROLLING\",\"amount\":90,\"unit\":\"DAY\"},"
                + "\"currency\":{\"mode\":\"EXPLICIT\",\"code\":\"CNY\"},\"aggregation\":\"SUM\","
                + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":\"过去90天消费超过1000元\",\"start\":7,\"end\":21},"
                + "\"candidateHints\":[],\"assumptions\":[]}"
                + "],"
                + "\"logicalExpression\":{\"type\":\"ALL\",\"children\":["
                + "{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},{\"type\":\"CLAUSE\",\"clauseId\":\"c2\"}]},"
                + "\"ambiguities\":[],\"unsupportedRequirements\":[]"
                + "}";
    }

    public static ContentHash hash(String text) {
        return ContentHash.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The exact registered reporting-currency revision every default fixture cites. */
    public static com.company.campaign.segment.intent.RegisteredDefaultSource.RegisteredDefaultRevision
            reportingCurrencyRevision() {
        return new com.company.campaign.segment.intent.RegisteredDefaultSource.RegisteredDefaultRevision(
                "reporting.currency", "1", hash("CNY"), "CNY");
    }

    /** An immutable registry holding exactly the reporting-currency default. */
    public static com.company.campaign.segment.intent.RegisteredDefaultSource currencyRegistry() {
        return new com.company.campaign.segment.intent.RegisteredDefaultSource.Registry(
                "registered-defaults/1", List.of(reportingCurrencyRevision()));
    }

    /** An immutable registry holding no defaults. */
    public static com.company.campaign.segment.intent.RegisteredDefaultSource emptyRegistry() {
        return new com.company.campaign.segment.intent.RegisteredDefaultSource.Registry(
                "registered-defaults/empty", List.of());
    }

    /** A registry whose revision differs from {@link #reportingCurrencyRevision()} only by version. */
    public static com.company.campaign.segment.intent.RegisteredDefaultSource driftedRegistry() {
        return new com.company.campaign.segment.intent.RegisteredDefaultSource.Registry(
                "registered-defaults/2",
                List.of(new com.company.campaign.segment.intent.RegisteredDefaultSource.RegisteredDefaultRevision(
                        "reporting.currency", "2", hash("USD"), "USD")));
    }
}