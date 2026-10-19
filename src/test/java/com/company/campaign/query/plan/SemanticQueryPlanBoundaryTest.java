package com.company.campaign.query.plan;

import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.QueryException;
import com.company.campaign.query.ast.QueryAst;
import com.company.campaign.query.ast.QueryAstCodec;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requirement 2 and 16: the plan is readable but not forgeable, and every internal construction path and
 * public constructor/codec rejects adversary-controlled nulls and malformed combinations with stable codes.
 */
class SemanticQueryPlanBoundaryTest {

    private static void assertInconsistent(Runnable construction) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(QueryException.class)
                .hasFieldOrPropertyWithValue("code", QueryErrorCodes.QUERY_PLAN_INCONSISTENT);
    }

    private static SemanticQueryPlan reconstruct(
            SemanticQueryPlan genuine,
            String planFormatVersion,
            List<ResolvedDependency> dependencies,
            DataScopePredicate dataScopePredicate,
            ContentHash planHash) {
        return new SemanticQueryPlan(
                planFormatVersion == null ? genuine.planFormatVersion() : planFormatVersion,
                genuine.plannerVersion(),
                genuine.compilerVersion(),
                genuine.queryAstVersion(),
                genuine.dslSchemaVersion(),
                genuine.canonicalizerVersion(),
                genuine.subject(),
                genuine.predicateRoot(),
                genuine.canonicalAstHash(),
                genuine.metadataBinding(),
                dependencies == null ? genuine.dependencies() : dependencies,
                genuine.audienceDependencies(),
                genuine.windowBindings(),
                genuine.currencyBindings(),
                dataScopePredicate == null ? genuine.dataScopePredicate() : dataScopePredicate,
                genuine.trustedContext(),
                planHash == null ? genuine.planHash() : planHash);
    }

    // -------------------------------------------------------- architecture: no public way in

    @Test
    void noPublicConstructorFactoryBuilderOrDeserializerCanCreateAPlan() {
        Class<SemanticQueryPlan> type = SemanticQueryPlan.class;

        assertThat(type.isRecord()).as("the plan is a proof boundary class, not a record").isFalse();
        assertThat(Modifier.isFinal(type.getModifiers())).isTrue();
        assertThat(type.getPackageName()).isEqualTo("com.company.campaign.query.plan");
        assertThat(type.getConstructors()).isEmpty();

        Constructor<?>[] declared = type.getDeclaredConstructors();
        assertThat(declared).hasSize(1);
        for (Constructor<?> constructor : declared) {
            assertThat(Modifier.isPublic(constructor.getModifiers())).isFalse();
            assertThat(Modifier.isProtected(constructor.getModifiers())).isFalse();
        }

        // No public static method on the plan hierarchy manufactures the plan.
        assertThat(Arrays.stream(type.getMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getReturnType() == type))
                .isEmpty();
    }

    @Test
    void thePlanCodecHasNoPublicParseOrReadEntryPoint() {
        for (Method method : SemanticQueryPlanCodec.class.getMethods()) {
            assertThat(method.getReturnType())
                    .as("codec method %s must not produce a plan", method.getName())
                    .isNotEqualTo(SemanticQueryPlan.class);
        }
        assertThat(Arrays.stream(SemanticQueryPlanCodec.class.getMethods())
                .map(Method::getName))
                .doesNotContain("read", "parse", "fromJson", "deserialize", "decode");
    }

    @Test
    void aDownstreamCallerCanReadEveryFieldOfAGenuinePlan() {
        SemanticQueryPlan plan = QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());
        assertThat(plan.planHash()).isNotNull();
        assertThat(new SemanticQueryPlanCodec().hash(plan)).isEqualTo(plan.planHash());
        assertThat(plan.toString()).startsWith("SemanticQueryPlan[").contains("planHash=");
    }

    // ---------------------------------------------- in-package: internal paths reject contradictions

    @Test
    void thePlannerRejectsACanonicalHashThatIsNotTheArtifactHash() {
        var genuine = SegmentValidationFixtures.validate(SegmentValidationFixtures.validDocument());
        ContentHash forged = ContentHash.sha256("forged".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> SegmentQueryPlanner.requireCanonicalHash(forged, genuine.canonicalSegment()))
                .isInstanceOf(QueryException.class)
                .hasFieldOrPropertyWithValue("code", QueryErrorCodes.QUERY_CANONICAL_HASH_MISMATCH);
        assertThatThrownBy(() -> SegmentQueryPlanner.requireCanonicalHash(null, genuine.canonicalSegment()))
                .isInstanceOf(QueryException.class)
                .hasFieldOrPropertyWithValue("code", QueryErrorCodes.QUERY_CANONICAL_HASH_MISMATCH);

        SegmentQueryPlanner.requireCanonicalHash(genuine.canonicalAstHash(), genuine.canonicalSegment());
    }

    @Test
    void internalConstructionRejectsAForgedPlanHash() {
        SemanticQueryPlan genuine = QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());
        ContentHash forged = ContentHash.sha256("forged".getBytes(StandardCharsets.UTF_8));
        assertInconsistent(() -> reconstruct(genuine, null, null, null, forged));
    }

    @Test
    void internalConstructionRejectsAVersionOtherThanTheSupportedOne() {
        SemanticQueryPlan genuine = QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());
        assertInconsistent(() -> reconstruct(genuine, "9.9", null, null, null));
    }

    @Test
    void internalConstructionRejectsAMissingDataScopePredicate() {
        SemanticQueryPlan genuine = QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());
        assertInconsistent(() -> new SemanticQueryPlan(
                genuine.planFormatVersion(), genuine.plannerVersion(), genuine.compilerVersion(),
                genuine.queryAstVersion(),
                genuine.dslSchemaVersion(), genuine.canonicalizerVersion(), genuine.subject(),
                genuine.predicateRoot(), genuine.canonicalAstHash(), genuine.metadataBinding(),
                genuine.dependencies(), genuine.audienceDependencies(),
                genuine.windowBindings(), genuine.currencyBindings(),
                null, genuine.trustedContext(), genuine.planHash()));
    }

    @Test
    void internalConstructionRejectsAnUnsortedOrRepeatedDependencyList() {
        SemanticQueryPlan genuine = QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());
        List<ResolvedDependency> reversed = genuine.dependencies().reversed();
        assertInconsistent(() -> reconstruct(genuine, null, reversed, null, null));

        ResolvedDependency first = genuine.dependencies().get(0);
        List<ResolvedDependency> repeated = List.of(first, first);
        assertInconsistent(() -> reconstruct(genuine, null, repeated, null, null));
    }

    @Test
    void internalConstructionRejectsADataScopePredicateForAnotherSubject() {
        SemanticQueryPlan genuine = QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());
        DataScopePredicate wrongSubject = new DataScopePredicate(
                DataScopePredicate.Requirement.SUBJECT_IN_DATASCOPE, SubjectKind.ORDER, "scope-008");
        assertInconsistent(() -> reconstruct(genuine, null, null, wrongSubject, null));
    }

    // -------------------------------------------------------- public AST constructors and codec

    @Test
    void queryAstConstructorsRejectNullsAndMalformedOperatorOperandCombinations() {
        assertThatThrownBy(() -> new QueryAst.Group(null, List.of()))
                .isInstanceOf(QueryException.class);
        assertThatThrownBy(() -> new QueryAst.Group(QueryAst.GroupKind.ALL, List.of()))
                .isInstanceOf(QueryException.class);
        assertThatThrownBy(() -> new QueryAst.Negation(null))
                .isInstanceOf(QueryException.class);
        assertThatThrownBy(() -> new QueryAst.Attribute(null,
                        com.company.campaign.metadata.domain.ComparisonOperator.EQUAL, null))
                .isInstanceOf(QueryException.class);

        com.company.campaign.segment.canonical.CanonicalDefinition definition =
                new com.company.campaign.segment.canonical.CanonicalDefinition(
                        com.company.campaign.metadata.MetadataKind.ATTRIBUTE,
                        "member.tier",
                        SubjectKind.MEMBER,
                        com.company.campaign.metadata.domain.SemanticDataType.ENUM,
                        com.company.campaign.metadata.domain.NullSemantics.UNKNOWN_VALUE,
                        com.company.campaign.metadata.domain.CurrencySemantics.NOT_APPLICABLE,
                        null);
        assertThatThrownBy(() -> new QueryAst.Attribute(definition,
                        com.company.campaign.metadata.domain.ComparisonOperator.EQUAL, null))
                .isInstanceOf(QueryException.class);
        assertThatThrownBy(() -> new QueryAst.Attribute(definition,
                        com.company.campaign.metadata.domain.ComparisonOperator.IS_NULL,
                        new com.company.campaign.segment.canonical.CanonicalValue.EnumValue("GOLD")))
                .isInstanceOf(QueryException.class);
    }

    @Test
    void theAstCodecRejectsNullInput() {
        assertThatThrownBy(() -> new QueryAstCodec().write(null))
                .isInstanceOf(QueryException.class)
                .hasFieldOrPropertyWithValue("code", QueryErrorCodes.QUERY_INPUT_MISSING);
        assertThatThrownBy(() -> new QueryAstCodec().hash(null))
                .isInstanceOf(QueryException.class);
    }

    @Test
    void thePlanCodecRejectsNullInput() {
        assertThatThrownBy(() -> new SemanticQueryPlanCodec().write(null))
                .isInstanceOf(QueryException.class)
                .hasFieldOrPropertyWithValue("code", QueryErrorCodes.QUERY_INPUT_MISSING);
    }
}