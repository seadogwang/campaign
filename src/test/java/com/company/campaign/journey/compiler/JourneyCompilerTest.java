package com.company.campaign.journey.compiler;

import com.company.campaign.builder.compiler.JourneyManifestFixtures;
import com.company.campaign.builder.compiler.ResolvedCampaignManifest;
import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.journey.graph.JourneyBranchKind;
import com.company.campaign.journey.graph.JourneyTopology;
import com.company.campaign.journey.graph.JourneyTopologyEdge;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Focused T20 section-3 tests: deterministic compilation, hash binding and non-forgeable output. */
class JourneyCompilerTest {

    private static final String MESSAGE_TEMPLATE = "message-template-1";

    private final JourneyCompiler compiler = new JourneyCompiler();

    private static List<ResolvedComponent> linearComponents() {
        return List.of(
                JourneyTestFixture.start("s"),
                JourneyTestFixture.message("m", MESSAGE_TEMPLATE),
                JourneyTestFixture.end("e", "END"),
                JourneyTestFixture.messageTemplate(MESSAGE_TEMPLATE));
    }

    private static JourneyTopology linearTopology() {
        return JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-m", "s", "m", "DEFAULT"),
                JourneyTestFixture.edge("m-e", "m", "e", "DEFAULT"));
    }

    private static JourneyCompilation compile(
            List<ResolvedComponent> components, JourneyTopology topology) {
        ResolvedCampaignManifest manifest = JourneyManifestFixtures.manifest(components);
        return new JourneyCompiler().compile(JourneyTestFixture.request(
                manifest, topology, JourneyTestFixture.index()));
    }

    @Test
    void identicalInputsProduceByteIdenticalMaterialAndHash() {
        ResolvedCampaignManifest manifest = JourneyManifestFixtures.manifest(linearComponents());
        JourneyCompileRequest request = JourneyTestFixture.request(
                manifest, linearTopology(), JourneyTestFixture.index());

        JourneyCompilation first = compiler.compile(request);
        for (int attempt = 0; attempt < 50; attempt++) {
            JourneyCompilation candidate = compiler.compile(request);
            assertThat(candidate.requireManifest().contentHash())
                    .isEqualTo(first.requireManifest().contentHash());
            assertThat(candidate.requireManifest().canonicalBytes())
                    .isEqualTo(first.requireManifest().canonicalBytes());
        }
    }

    @Test
    void theManifestFreezesVersionsSourceHashesAndStartIdentity() {
        ResolvedCampaignManifest manifest = JourneyManifestFixtures.manifest(linearComponents());
        JourneyTopology topology = linearTopology();
        JourneyManifest compiled = compiler.compile(JourneyTestFixture.request(
                manifest, topology, JourneyTestFixture.index())).requireManifest();

        assertThat(compiled.journeyFormatVersion()).isEqualTo(JourneyVersions.JOURNEY_FORMAT_VERSION);
        assertThat(compiled.compilerVersion()).isEqualTo(JourneyVersions.JOURNEY_COMPILER_VERSION);
        assertThat(compiled.runtimeSchemaVersion()).isEqualTo(JourneyVersions.JOURNEY_RUNTIME_SCHEMA_VERSION);
        assertThat(compiled.sourceManifestHash()).isEqualTo(manifest.manifestHash());
        assertThat(compiled.topologyHash()).isEqualTo(topology.topologyHash());
        assertThat(compiled.startNodeLogicalId()).isEqualTo("s");
        assertThat(compiled.terminalNodeLogicalIds()).containsExactly("e");
        assertThat(compiled.nodes()).extracting(node -> node.logicalId()).containsExactly("e", "m", "s");
        assertThat(compiled.topologicalOrder()).containsExactly("s", "m", "e");
        assertThat(compiled.edges()).hasSize(2);
    }

    @Test
    void aPayloadChangeChangesTheContentHash() {
        JourneyManifest base = compile(linearComponents(), linearTopology()).requireManifest();

        List<ResolvedComponent> changed = List.of(
                JourneyTestFixture.start("s"),
                JourneyTestFixture.message("m", MESSAGE_TEMPLATE),
                JourneyTestFixture.end("e", "EXIT"),
                JourneyTestFixture.messageTemplate(MESSAGE_TEMPLATE));
        JourneyManifest variant = compile(changed, linearTopology()).requireManifest();

        assertThat(variant.contentHash()).isNotEqualTo(base.contentHash());
    }

    @Test
    void anEdgeBranchChangeChangesTheContentHash() {
        List<ResolvedComponent> conditionComponents = List.of(
                JourneyTestFixture.start("s"),
                JourneyTestFixture.condition("c"),
                JourneyTestFixture.end("t", "END"),
                JourneyTestFixture.end("f", "EXIT"));
        JourneyTopology trueFirst = JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-c", "s", "c", "DEFAULT"),
                JourneyTestFixture.edge("c-t", "c", "t", "TRUE"),
                JourneyTestFixture.edge("c-f", "c", "f", "FALSE"));
        JourneyTopology swapped = JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-c", "s", "c", "DEFAULT"),
                JourneyTestFixture.edge("c-t", "c", "t", "FALSE"),
                JourneyTestFixture.edge("c-f", "c", "f", "TRUE"));
        JourneyManifest a = compile(conditionComponents, trueFirst).requireManifest();
        JourneyManifest b = compile(conditionComponents, swapped).requireManifest();

        assertThat(a.contentHash()).isNotEqualTo(b.contentHash());
    }

    @Test
    void aCatalogReferenceChangeChangesTheNodeAndContentHash() {
        ResolvedCatalogReference reference = new ResolvedCatalogReference(
                "template", "MESSAGE_TEMPLATE", MESSAGE_TEMPLATE, "message-welcome-01");
        List<ResolvedComponent> withReference = List.of(
                JourneyTestFixture.start("s"),
                JourneyTestFixture.message("m", MESSAGE_TEMPLATE, List.of(reference)),
                JourneyTestFixture.end("e", "END"),
                JourneyTestFixture.messageTemplate(MESSAGE_TEMPLATE));

        JourneyManifest plain = compile(linearComponents(), linearTopology()).requireManifest();
        JourneyManifest referenced = compile(withReference, linearTopology()).requireManifest();

        assertThat(referenced.node("m").catalogReferenceHash())
                .isNotEqualTo(plain.node("m").catalogReferenceHash());
        assertThat(referenced.contentHash()).isNotEqualTo(plain.contentHash());
    }

    @Test
    void aSourceManifestChangeChangesTheSourceHashBinding() {
        ResolvedCampaignManifest first = JourneyManifestFixtures.manifest(linearComponents());
        List<ResolvedComponent> changed = List.of(
                JourneyTestFixture.start("s"),
                JourneyTestFixture.message("m", "message-template-2"),
                JourneyTestFixture.end("e", "END"),
                JourneyTestFixture.messageTemplate("message-template-2"));
        ResolvedCampaignManifest second = JourneyManifestFixtures.manifest(changed);

        JourneyManifest a = compiler.compile(JourneyTestFixture.request(
                first, linearTopology(), JourneyTestFixture.index())).requireManifest();
        JourneyManifest b = compiler.compile(JourneyTestFixture.request(
                second, linearTopology(), JourneyTestFixture.index())).requireManifest();

        assertThat(second.manifestHash()).isNotEqualTo(first.manifestHash());
        assertThat(b.sourceManifestHash()).isNotEqualTo(a.sourceManifestHash());
    }

    @Test
    void anInvalidGraphFailsClosedWithoutAManifest() {
        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.message("m", MESSAGE_TEMPLATE),
                JourneyTestFixture.messageTemplate(MESSAGE_TEMPLATE));
        JourneyCompilation compilation = compile(components, JourneyTestFixture.topology());

        assertThat(compilation.isCompiled()).isFalse();
        assertThat(compilation.issues()).isNotEmpty();
        assertThatThrownBy(compilation::requireManifest)
                .isInstanceOf(JourneyCompileException.class);
    }

    @Test
    void theManifestExposesDefensiveImmutableCollections() {
        JourneyManifest compiled = compile(linearComponents(), linearTopology()).requireManifest();
        assertThatThrownBy(() -> compiled.nodes().add(compiled.nodes().get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> compiled.edges().add(
                new JourneyTopologyEdge("x", "s", "e", JourneyBranchKind.DEFAULT)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unsupportedCompilerOrRuntimeVersionIsRefused() {
        ResolvedCampaignManifest manifest = JourneyManifestFixtures.manifest(linearComponents());
        assertThatThrownBy(() -> new JourneyCompileRequest(
                manifest, linearTopology(), JourneyTestFixture.index(),
                "other-compiler", JourneyVersions.JOURNEY_RUNTIME_SCHEMA_VERSION))
                .isInstanceOf(JourneyCompileException.class)
                .hasFieldOrPropertyWithValue("code", JourneyCompileErrorCodes.VERSION_UNSUPPORTED);
    }
}
