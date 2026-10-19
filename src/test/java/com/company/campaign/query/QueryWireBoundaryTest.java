package com.company.campaign.query;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.query.ast.QueryAst;
import com.company.campaign.query.plan.ResolvedDependency;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Finding 4: every public {@link QueryWire} entry point rejects null input with the stable bounded T08
 * vocabulary, so neither AST nor plan codecs can leak a raw {@code NullPointerException} or
 * {@code ClassCastException}, and no payload or thrown cause ever escapes.
 */
class QueryWireBoundaryTest {

    private static List<Method> publicWireMethods() {
        List<Method> methods = new ArrayList<>();
        for (Method method : QueryWire.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())) {
                methods.add(method);
            }
        }
        methods.sort(java.util.Comparator.comparing(Method::getName));
        return methods;
    }

    private static Throwable invokeWithNulls(Method method) {
        Object[] arguments = new Object[method.getParameterCount()];
        try {
            method.invoke(null, arguments);
            return null;
        } catch (InvocationTargetException exception) {
            return exception.getCause();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void everyPublicWireMethodRejectsNullWithAStableCodedFailure() {
        List<Method> methods = publicWireMethods();
        assertThat(methods).hasSize(8);

        for (Method method : methods) {
            Throwable thrown = invokeWithNulls(method);
            assertThat(thrown)
                    .as("QueryWire.%s(null)", method.getName())
                    .isInstanceOf(QueryException.class)
                    .hasFieldOrPropertyWithValue("code", QueryErrorCodes.QUERY_AST_INVALID);
            assertThat(thrown).isNotInstanceOf(NullPointerException.class);
            assertThat(thrown).isNotInstanceOf(ClassCastException.class);
            assertThat(thrown.getCause()).isNull();
        }
    }

    @Test
    void aNullProjectionFailureEchoesNoPayloadAndKeepsClosedDetails() {
        QueryException failure = catchThrowableOfType(QueryException.class, () -> QueryWire.definition(null));
        assertThat(failure.getMessage()).isEqualTo("A Query wire projection requires a non-null input.");
        assertThat(failure.details()).containsOnlyKeys("invariant");
        assertThat(failure.details().get("invariant")).isEqualTo("definition");
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void theWireStillProjectsGenuineCanonicalTypesDeterministically() {
        SemanticQueryPlan plan = QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());
        QueryAst.Group root = (QueryAst.Group) plan.predicateRoot();

        QueryAst.Attribute attribute = root.children().stream()
                .filter(QueryAst.Attribute.class::isInstance)
                .map(QueryAst.Attribute.class::cast)
                .findFirst()
                .orElseThrow();
        QueryAst.Metric metric = root.children().stream()
                .filter(QueryAst.Metric.class::isInstance)
                .map(QueryAst.Metric.class::cast)
                .findFirst()
                .orElseThrow();
        ResolvedDependency.MetricDependency metricDependency = plan.dependencies().stream()
                .filter(ResolvedDependency.MetricDependency.class::isInstance)
                .map(ResolvedDependency.MetricDependency.class::cast)
                .findFirst()
                .orElseThrow();

        assertThat(QueryWire.definition(attribute.definition()))
                .containsKeys("kind", "id", "subject", "dataType", "nullSemantics", "currencySemantics")
                .containsEntry("id", "member.tier");
        assertThat(QueryWire.metricDefinition(metric.definition()))
                .containsKeys("kind", "id", "aggregation", "emptySetBehavior", "windowBoundarySemantics")
                .containsEntry("kind", "METRIC");
        assertThat(QueryWire.window(metric.window())).containsEntry("type", "ROLLING").containsKey("days");
        assertThat(QueryWire.value(metric.operand()))
                .containsEntry("kind", "MONEY").containsEntry("currency", "CNY");
        assertThat(QueryWire.filter(metricDependency.filter())).containsKey("entries");

        SemanticQueryPlan existsPlan = QueryPlanFixtures.plan(SegmentValidationFixtures.exists(
                "n-e", "member.orders", SegmentValidationFixtures.lifetime(),
                SegmentValidationFixtures.attribute("n-c", "order.channel", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue("ONLINE")))));
        QueryAst.Exists exists = (QueryAst.Exists) existsPlan.predicateRoot();
        assertThat(QueryWire.relation(exists.relation()))
                .containsEntry("kind", "RELATION").containsEntry("id", "member.orders");

        // Repeated projection is value-stable.
        assertThat(QueryWire.value(metric.operand())).isEqualTo(QueryWire.value(metric.operand()));
        assertThat(QueryWire.definition(attribute.definition()))
                .isEqualTo(QueryWire.definition(attribute.definition()));
        assertThat(QueryWire.window(metric.window())).isEqualTo(QueryWire.window(metric.window()));
    }
}
