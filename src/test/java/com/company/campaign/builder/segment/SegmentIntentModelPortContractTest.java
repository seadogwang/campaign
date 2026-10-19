package com.company.campaign.builder.segment;

import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The safe request contract: the request handed to a model port is bounded, carries only a permitted
 * definition projection and never a token, credential or restricted host field.
 */
class SegmentIntentModelPortContractTest {

    private static final List<String> FORBIDDEN_COMPONENT_TOKENS = List.of(
            "token", "credential", "secret", "apikey", "authorization", "tenant", "program", "user",
            "datascope", "scope", "permission", "jdbc", "datasource", "connection", "table", "column",
            "catalog", "gate", "save", "publish", "runtime", "sql");

    private final SegmentModelRequestFactory factory = new SegmentModelRequestFactory();

    @Test
    void theRequestAndItsNestedRecordsCarryNoRestrictedHostField() {
        assertNoForbiddenComponents(SegmentModelRequest.class);
        assertNoForbiddenComponents(SegmentModelRequest.MetadataSnapshotPin.class);
        assertNoForbiddenComponents(SegmentModelRequest.MetadataProjection.class);
        assertNoForbiddenComponents(SegmentModelRequest.TemporalContext.class);
        assertNoForbiddenComponents(SegmentModelRequest.ModelDefaults.class);
        assertNoForbiddenComponents(SegmentModelRequest.EnumAlias.class);
        assertNoForbiddenComponents(SegmentModelRequest.MetricFilterProjection.class);
    }

    @Test
    void theRequestPinsTheIntentVersionAndTheExactMetadataSnapshot() {
        SegmentModelRequest request = factory.build("Gold会员", "zh-CN", SliceTestFixtures.snapshot());

        assertThat(request.intentSchemaVersion().value()).isEqualTo("1.3");
        assertThat(request.localeTag()).isEqualTo("zh-CN");
        assertThat(request.instructions()).isEqualTo(SegmentModelRequest.INSTRUCTIONS);
        assertThat(request.metadataSnapshot().snapshotId()).isEqualTo("metadata-17");
        assertThat(request.metadataSnapshot().contentHash()).isEqualTo(SliceTestFixtures.snapshot().contentHash());
        assertThat(request.instructions())
                .contains("scopeFilters")
                .contains("never flatten")
                .contains("uppercase machine code")
                .contains("source span must be exact");
    }

    @Test
    void theProjectionCarriesOnlyPermittedDefinitionsAndTheirResolutionSemantics() {
        SegmentModelRequest request = factory.build("Gold会员", "zh-CN", SliceTestFixtures.snapshot());

        assertThat(request.permittedDefinitions()).hasSize(5);
        SegmentModelRequest.MetadataProjection tier = request.permittedDefinitions().stream()
                .filter(projection -> projection.stableId().equals("member.tier"))
                .findFirst()
                .orElseThrow();
        assertThat(tier.kind().name()).isEqualTo("ATTRIBUTE");
        assertThat(tier.enumAliases()).anySatisfy(value -> assertThat(value.code()).isEqualTo("GOLD"));
        assertThat(tier.allowedOperators()).isNotEmpty();
        assertThat(tier.relationSourceEntity()).isNull();
    }

    @Test
    void anOversizedUtteranceFailsClosedBeforeAnyProviderCall() {
        String oversized = "x".repeat(SegmentModelLimits.MAX_UTTERANCE_LENGTH + 1);
        assertThatThrownBy(() -> factory.build(oversized, "zh-CN", SliceTestFixtures.snapshot()))
                .isInstanceOf(SegmentAdapterException.class);
    }

    @Test
    void theServiceCallsTheInjectedPortExactlyOnce() {
        SliceTestFixtures.StubModelPort port = SliceTestFixtures.StubModelPort.returning(
                SliceTestFixtures.enumTierJson());
        SliceTestFixtures.service(port).invoke(SliceTestFixtures.request("Gold会员", port));

        assertThat(port.invocations()).isEqualTo(1);
        assertThat(port.lastRequest().utterance()).isEqualTo("Gold会员");
    }

    private static void assertNoForbiddenComponents(Class<?> type) {
        assertThat(type.isRecord()).isTrue();
        for (RecordComponent component : type.getRecordComponents()) {
            for (String word : words(component.getName())) {
                assertThat(FORBIDDEN_COMPONENT_TOKENS)
                        .as("%s.%s must not carry a restricted field", type.getSimpleName(), component.getName())
                        .doesNotContain(word);
            }
        }
    }

    /** Splits a camelCase record component name into lower-case words for exact token matching. */
    private static List<String> words(String name) {
        return java.util.Arrays.stream(name.split("(?=[A-Z])"))
                .map(word -> word.toLowerCase(java.util.Locale.ROOT))
                .toList();
    }
}
