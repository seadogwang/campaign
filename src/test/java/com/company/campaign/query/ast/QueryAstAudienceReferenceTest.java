package com.company.campaign.query.ast;

import com.company.campaign.segment.canonical.CanonicalAudienceVersionRef;
import com.company.campaign.segment.canonical.CanonicalNode;
import com.company.campaign.segment.canonical.CanonicalSegmentCodec;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T18: one Existing Audience Reference compiles into the one closed Query AST leaf that carries its pin.
 *
 * <p>The leaf must survive exactly where it was declared — at the root, under a commutative group and under a
 * {@code NOT} — because dropping, inlining or replacing it would change the document's meaning. It must carry
 * the immutable pin and nothing else, and its canonical bytes must be the T07 canonical reference node's own
 * bytes, so an AST compiled from a canonical tree adds no semantic field of its own.
 */
class QueryAstAudienceReferenceTest {

    private static final CanonicalAudienceVersionRef PIN = new CanonicalAudienceVersionRef(
            "audience-vip", "version-7",
            ContentHash.sha256("audience-vip/version-7".getBytes(StandardCharsets.UTF_8)));

    private static CanonicalNode.AudienceReference reference() {
        return new CanonicalNode.AudienceReference(PIN);
    }

    @Test
    void aReferenceAtTheRootCompilesToExactlyOneLeafCarryingOnlyThePin() {
        QueryAst compiled = QueryAstCompiler.compile(reference());

        assertThat(compiled).isInstanceOf(QueryAst.AudienceReference.class);
        QueryAst.AudienceReference leaf = (QueryAst.AudienceReference) compiled;
        assertThat(leaf.reference()).isEqualTo(PIN);
        assertThat(leaf.reference().audienceId()).isEqualTo("audience-vip");
        assertThat(leaf.reference().versionId()).isEqualTo("version-7");
        assertThat(leaf.reference().versionHash()).isEqualTo(PIN.versionHash());
    }

    @Test
    void aReferenceNestedUnderAGroupingIsNeitherSkippedNorInlined() {
        CanonicalNode.Group group = new CanonicalNode.Group(
                CanonicalNode.GroupKind.ALL, List.of(reference()));

        QueryAst compiled = QueryAstCompiler.compile(group);

        assertThat(compiled).isInstanceOf(QueryAst.Group.class);
        assertThat(((QueryAst.Group) compiled).children()).hasSize(1);
        assertThat(((QueryAst.Group) compiled).children().get(0))
                .isInstanceOf(QueryAst.AudienceReference.class);

        QueryAst negation = QueryAstCompiler.compile(new CanonicalNode.Negation(reference()));
        assertThat(negation).isInstanceOf(QueryAst.Negation.class);
        assertThat(((QueryAst.Negation) negation).child()).isInstanceOf(QueryAst.AudienceReference.class);
    }

    @Test
    void theAstLeafSerializesAsTheCanonicalReferenceNodeItself() {
        CanonicalSegmentCodec canonicalCodec = new CanonicalSegmentCodec();
        QueryAst leaf = QueryAstCompiler.compile(reference());

        assertThat(new QueryAstCodec().write(leaf)).isEqualTo(canonicalCodec.writeNode(reference()));
    }

    @Test
    void aNullCanonicalReferenceIsRejectedRatherThanCompiled() {
        assertThatThrownBy(() -> new QueryAst.AudienceReference(null))
                .isInstanceOf(com.company.campaign.query.QueryException.class);
    }
}