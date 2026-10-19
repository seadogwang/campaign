package com.company.campaign.query.ast;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.canonical.CanonicalSegment;
import com.company.campaign.segment.canonical.CanonicalSegmentCodec;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The AST adds no second semantic model: compiling the accepted canonical tree one-for-one serializes to
 * exactly the canonical bytes, so the two projections can never drift apart.
 */
class QueryAstCodecTest {

    private static final CanonicalSegmentCodec CANONICAL = new CanonicalSegmentCodec();
    private static final QueryAstCodec AST = new QueryAstCodec();

    @Test
    void compiledAstBytesAndHashMatchTheCanonicalPredicateBytes() {
        SegmentValidationResult result =
                SegmentValidationFixtures.validate(SegmentValidationFixtures.validDocument());
        CanonicalSegment segment = result.canonicalSegment();

        QueryAst compiled = QueryAstCompiler.compile(segment);

        assertThat(AST.write(compiled)).isEqualTo(CANONICAL.writeNode(segment.predicate()));
        assertThat(AST.writeString(compiled)).isEqualTo(
                new String(CANONICAL.writeNode(segment.predicate()), java.nio.charset.StandardCharsets.UTF_8));
        assertThat(AST.hash(compiled)).isEqualTo(CANONICAL.hashNode(segment.predicate()));
    }

    @Test
    void compilationMapsEveryNodeKindOneForOne() {
        SegmentValidationResult result = SegmentValidationFixtures.validate(SegmentValidationFixtures.all("n-root", List.of(
                SegmentValidationFixtures.not("n-not", SegmentValidationFixtures.attribute(
                        "n-name", "member.name", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.stringValue("x")))),
                SegmentValidationFixtures.any("n-any", List.of(
                        SegmentValidationFixtures.tierEquals("n-tier", "GOLD"),
                        SegmentValidationFixtures.exists("n-e", "member.orders", SegmentValidationFixtures.lifetime(),
                                SegmentValidationFixtures.attribute("n-c", "order.channel",
                                        ComparisonOperator.EQUAL,
                                        SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue("ONLINE")))))))));

        QueryAst root = QueryAstCompiler.compile(result.canonicalSegment());
        assertThat(root).isInstanceOf(QueryAst.Group.class);
        QueryAst.Group group = (QueryAst.Group) root;
        assertThat(group.kind()).isEqualTo(QueryAst.GroupKind.ALL);
        assertThat(group.children()).hasSize(2);

        QueryAst negation = group.children().stream()
                .filter(QueryAst.Negation.class::isInstance)
                .findFirst()
                .orElseThrow();
        assertThat(((QueryAst.Negation) negation).child()).isInstanceOf(QueryAst.Attribute.class);

        QueryAst.Group anyGroup = (QueryAst.Group) group.children().stream()
                .filter(QueryAst.Group.class::isInstance)
                .findFirst()
                .orElseThrow();
        QueryAst.Exists exists = (QueryAst.Exists) anyGroup.children().stream()
                .filter(QueryAst.Exists.class::isInstance)
                .findFirst()
                .orElseThrow();
        assertThat(exists.relation().id()).isEqualTo("member.orders");
        assertThat(exists.child()).isInstanceOf(QueryAst.Attribute.class);
    }

    @Test
    void repeatedCompilationIsByteIdentical() {
        SegmentValidationResult result =
                SegmentValidationFixtures.validate(SegmentValidationFixtures.validDocument());
        byte[] expected = AST.write(QueryAstCompiler.compile(result.canonicalSegment()));
        for (int index = 0; index < 50; index++) {
            assertThat(AST.write(QueryAstCompiler.compile(result.canonicalSegment()))).isEqualTo(expected);
        }
    }
}