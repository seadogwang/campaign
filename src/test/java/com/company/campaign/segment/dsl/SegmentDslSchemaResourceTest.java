package com.company.campaign.segment.dsl;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.dsl.contract.TemporalGrammar;
import com.company.campaign.segment.dsl.predicate.PredicateType;
import com.company.campaign.segment.dsl.value.ValueKind;
import com.company.campaign.segment.dsl.window.WindowType;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the checked-in Draft 2020-12 schema resource: its declared draft/id/version, its closedness and its
 * alignment with every Java discriminator and required root field. No schema validator is downloaded or added
 * as a dependency; the resource is read and inspected directly.
 */
class SegmentDslSchemaResourceTest {

    private static final String SCHEMA_RESOURCE = "/schema/segment-dsl-1.3.schema.json";

    private static JsonNode schema() {
        try (InputStream stream = SegmentDslSchemaResourceTest.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            assertThat(stream).as("checked-in schema resource %s", SCHEMA_RESOURCE).isNotNull();
            return new CanonicalJson().readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read the checked-in JSON Schema", exception);
        }
    }

    private static Set<String> names(Enum<?>[] values) {
        Set<String> names = new LinkedHashSet<>();
        for (Enum<?> value : values) {
            names.add(value.name());
        }
        return names;
    }

    private static Set<String> constants(JsonNode definitions, List<String> definitionNames, String discriminator) {
        Set<String> values = new LinkedHashSet<>();
        for (String name : definitionNames) {
            JsonNode property = definitions.get(name).get("properties").get(discriminator);
            assertThat(property).as("%s.%s", name, discriminator).isNotNull();
            values.add(property.get("const").asText());
        }
        return values;
    }

    @Test
    void declaresDraftIdAndSchemaVersion() {
        JsonNode schema = schema();
        assertThat(schema.get("$schema").asText()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(schema.get("$id").asText()).isNotBlank();
        assertThat(schema.get("x-segment-dsl-schema-version").asText()).isEqualTo("1.3");
        assertThat(schema.get("properties").get("schemaVersion").get("const").asText()).isEqualTo("1.3");
        assertThat(schema.get("properties").get("metadataSchemaVersion").get("const").asText()).isEqualTo("1.0");
    }

    @Test
    void isClosedAndRequiresEveryRootBindingAndHashField() {
        JsonNode root = schema();
        assertThat(root.get("additionalProperties").asBoolean()).isFalse();
        Set<String> required = new LinkedHashSet<>();
        root.get("required").forEach(node -> required.add(node.asText()));
        assertThat(required).containsExactlyInAnyOrder(
                "schemaVersion", "subject", "metadataSnapshotId", "metadataSnapshotVersion",
                "metadataSchemaVersion", "metadataContentHash", "predicate", "editableDslHash");
    }

    @Test
    void everyRootFieldIsDeclared() {
        JsonNode properties = schema().get("properties");
        for (String field : List.of(
                "schemaVersion", "subject", "metadataSnapshotId", "metadataSnapshotVersion",
                "metadataSchemaVersion", "metadataContentHash", "predicate", "editableDslHash")) {
            assertThat(properties.has(field)).as("root field %s", field).isTrue();
        }
    }

    @Test
    void modelsEveryPredicateDiscriminatorWithNotAsSingularChild() {
        JsonNode definitions = schema().get("$defs");
        Set<String> predicateTypes = constants(definitions, List.of(
                "predicateAll", "predicateAny", "predicateNot",
                "predicateAttribute", "predicateMetric", "predicateMetricExpression", "predicateExists",
                "predicateAudienceReference"), "type");
        assertThat(predicateTypes).containsExactlyInAnyOrderElementsOf(names(PredicateType.values()));

        JsonNode not = definitions.get("predicateNot");
        assertThat(not.get("additionalProperties").asBoolean()).isFalse();
        assertThat(not.get("required").toString()).contains("child").doesNotContain("children");
        assertThat(not.get("properties").has("children")).isFalse();

        JsonNode expression = definitions.get("predicateMetricExpression");
        assertThat(expression.get("additionalProperties").asBoolean()).isFalse();
        assertThat(expression.get("properties").get("expression").get("$ref").asText())
                .endsWith("/metricExpression");
        assertThat(definitions.get("metricExpressionDivide").get("properties")
                .get("zeroDenominator").get("const").asText()).isEqualTo("EXCLUDED");
    }

    @Test
    void modelsEveryTypedValueDiscriminatorAndTheScalarSubset() {
        JsonNode definitions = schema().get("$defs");
        Set<String> valueKinds = constants(definitions, List.of(
                "valueString", "valueInteger", "valueDecimal", "valueBoolean", "valueDate",
                "valueDateTime", "valueEnum", "valueMoney", "valueList", "valueRange"), "kind");
        assertThat(valueKinds).containsExactlyInAnyOrderElementsOf(names(ValueKind.values()));

        // LIST items and RANGE bounds must reference the scalar-only subset, never the full union.
        assertThat(definitions.get("valueList").get("properties").get("items").get("items").get("$ref").asText())
                .endsWith("/scalarValue");
        assertThat(definitions.get("valueRange").get("properties").get("lower").get("$ref").asText())
                .endsWith("/scalarValue");
        assertThat(definitions.get("valueRange").get("properties").get("upper").get("$ref").asText())
                .endsWith("/scalarValue");
    }

    @Test
    void modelsEveryWindowDiscriminatorAndUnitVocabulary() {
        JsonNode definitions = schema().get("$defs");
        Set<String> windowTypes = constants(
                definitions, List.of(
                        "windowRolling", "windowLifetime", "windowFixed", "windowRelativeToAttribute",
                        "windowSelectedEvent", "windowRelativeToEvent"),
                "type");
        assertThat(windowTypes).containsExactlyInAnyOrderElementsOf(names(WindowType.values()));

        Set<String> units = new LinkedHashSet<>();
        definitions.get("windowRolling").get("properties").get("unit").get("enum").forEach(n -> units.add(n.asText()));
        assertThat(units).containsExactlyInAnyOrder("DAY", "WEEK");
        assertThat(units).doesNotContain("MONTH", "YEAR");
    }

    @Test
    void operatorAndSubjectVocabulariesMatchTheJavaContracts() {
        JsonNode definitions = schema().get("$defs");
        Set<String> operators = new LinkedHashSet<>();
        definitions.get("operator").get("enum").forEach(node -> operators.add(node.asText()));
        assertThat(operators).containsExactlyInAnyOrderElementsOf(names(ComparisonOperator.values()));

        Set<String> subjects = new LinkedHashSet<>();
        schema().get("properties").get("subject").get("enum").forEach(node -> subjects.add(node.asText()));
        assertThat(subjects).containsExactlyInAnyOrderElementsOf(names(SubjectKind.values()));
    }

    @Test
    void expressesTheOperatorValueMatrixAsAClosedConditionalPartition() {
        JsonNode definitions = schema().get("$defs");
        Set<String> allOperators = names(ComparisonOperator.values());

        for (String predicate : List.of("predicateAttribute", "predicateMetric")) {
            Set<String> seen = new LinkedHashSet<>();
            JsonNode branches = definitions.get(predicate).get("allOf");
            assertThat(branches).as("%s operator/value branches", predicate).isNotNull();
            for (JsonNode branch : branches) {
                JsonNode operatorEnum = resolve(definitions, branch.get("if").get("properties").get("operator")).get("enum");
                Set<String> group = new LinkedHashSet<>();
                operatorEnum.forEach(node -> group.add(node.asText()));
                assertThat(seen).as("%s partitions the operator set without overlap", predicate).doesNotContainAnyElementsOf(group);
                seen.addAll(group);
            }
            assertThat(seen).as("%s covers every operator exactly", predicate)
                    .containsExactlyInAnyOrderElementsOf(allOperators);

            assertValueBranch(branches, definitions, Set.of("IS_NULL", "IS_NOT_NULL"), "forbidsValue", null);
            assertValueBranch(branches, definitions, Set.of("IN", "NOT_IN"), "requiresValue", "valueList");
            assertValueBranch(branches, definitions, Set.of("BETWEEN"), "requiresValue", "valueRange");
            assertValueBranch(branches, definitions, scalarOperators(), "requiresValue", "scalarValue");
        }
    }

    /** Every operator other than the NULL, LIST and RANGE operators is a scalar operator. */
    private static Set<String> scalarOperators() {
        Set<String> scalar = names(ComparisonOperator.values());
        scalar.removeAll(Set.of("IS_NULL", "IS_NOT_NULL", "IN", "NOT_IN", "BETWEEN"));
        return scalar;
    }

    private static void assertValueBranch(JsonNode branches, JsonNode definitions, Set<String> operators,
                                          String expectation, String valueRef) {
        JsonNode branch = null;
        for (JsonNode candidate : branches) {
            JsonNode operatorEnum = resolve(definitions, candidate.get("if").get("properties").get("operator")).get("enum");
            Set<String> group = new LinkedHashSet<>();
            operatorEnum.forEach(node -> group.add(node.asText()));
            if (group.equals(operators)) {
                branch = candidate;
                break;
            }
        }
        assertThat(branch).as("branch for operators %s", operators).isNotNull();
        JsonNode then = branch.get("then");
        if ("forbidsValue".equals(expectation)) {
            assertThat(then.get("not").get("required").toString()).contains("value");
            assertThat(then.has("required")).isFalse();
        } else {
            assertThat(then.get("required").toString()).contains("value");
            assertThat(then.get("properties").get("value").get("$ref").asText())
                    .as("value constraint for %s", operators)
                    .endsWith("/" + valueRef);
        }
    }

    /** Resolves a {@code $ref} to its definition, or returns the node when it is already inline. */
    private static JsonNode resolve(JsonNode definitions, JsonNode node) {
        JsonNode reference = node.get("$ref");
        if (reference == null) {
            return node;
        }
        String name = reference.asText().substring(reference.asText().lastIndexOf('/') + 1);
        return definitions.get(name);
    }

    @Test
    void temporalPatternsAgreeWithTheSharedJavaGrammar() {
        JsonNode definitions = schema().get("$defs");
        Pattern date = Pattern.compile(
                definitions.get("valueDate").get("properties").get("value").get("pattern").asText());
        Pattern dateTime = Pattern.compile(
                definitions.get("valueDateTime").get("properties").get("value").get("pattern").asText());

        // Lexical forms only: whether a syntactically valid date names a real calendar day (for example
        // 2026-13-01) is a Java-only invariant that a regular expression cannot express.
        List<String> dates = List.of(
                "2026-01-01", "2024-02-29", "+2026-01-01", "12026-01-01", "2026-1-1", "2026-01-011", "2026-01-01T00");
        for (String candidate : dates) {
            assertThat(date.matcher(candidate).matches())
                    .as("schema DATE pattern and Java grammar agree on %s", candidate)
                    .isEqualTo(TemporalGrammar.isExactLocalDate(candidate));
        }

        List<String> dateTimes = List.of(
                "2026-01-01T00:00:00Z", "2026-01-01T00:00:00+08:00", "2026-01-01T00:00:00.250Z",
                "2026-01-01T00:00:00.123456789-05:30", "2026-01-01T00:00+08:00",
                "2026-01-01T00:00:00+0800", "2026-01-01T00:00:00", "2026-01-01t00:00:00z",
                "2026-01-01T00:00:00.1234567890Z", "2026-01-01T00:00:00Z ", "2026-01-01T00:00:00.Z");
        for (String candidate : dateTimes) {
            assertThat(dateTime.matcher(candidate).matches())
                    .as("schema DATETIME pattern and Java grammar agree on %s", candidate)
                    .isEqualTo(TemporalGrammar.isExactOffsetDateTime(candidate));
        }

        // FIXED boundaries share the same exact pattern as DATETIME.
        JsonNode fixedStart = definitions.get("windowFixed").get("properties").get("start");
        assertThat(fixedStart.get("pattern").asText())
                .isEqualTo(definitions.get("valueDateTime").get("properties").get("value").get("pattern").asText());
        assertThat(definitions.get("windowFixed").get("properties").get("end").get("pattern").asText())
                .isEqualTo(fixedStart.get("pattern").asText());
    }

    @Test
    void modelsTheAudienceReferencePinAsAClosedFiveFieldShape() {
        JsonNode reference = schema().get("$defs").get("predicateAudienceReference");

        assertThat(reference.get("additionalProperties").asBoolean()).isFalse();
        Set<String> fields = new LinkedHashSet<>();
        reference.get("properties").fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(
                "nodeId", "type", "audienceId", "versionId", "versionHash");
        Set<String> required = new LinkedHashSet<>();
        reference.get("required").forEach(node -> required.add(node.asText()));
        assertThat(required).containsExactlyInAnyOrderElementsOf(fields);
        assertThat(reference.get("properties").get("type").get("const").asText())
                .isEqualTo(PredicateType.AUDIENCE_REFERENCE.name());
        assertThat(reference.get("properties").get("versionHash").get("pattern").asText())
                .isEqualTo("^sha256:[0-9a-f]{64}$");

        // The property-name set above is the whole surface: `additionalProperties: false` plus an exact,
        // exhaustive property list means no name, query, SQL, owner, scope, permission, canonical byte,
        // dependency list or mutable current/latest pointer is representable anywhere in the node.
        assertThat(reference.get("properties").size()).isEqualTo(5);
    }

    @Test
    void prohibitsOpenValueAnyAndOpenObjectShapes() {
        String text = schema().toString();
        assertThat(text).doesNotContain("\"any\"");
        assertThat(text).doesNotContain("OBJECT");
        // Every value/type discriminator is an exact closed const or enum, never an open string.
        assertThat(schema().get("$defs").get("valueString").get("additionalProperties").asBoolean()).isFalse();
    }
}
