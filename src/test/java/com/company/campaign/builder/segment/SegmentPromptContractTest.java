package com.company.campaign.builder.segment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SegmentPromptContractTest {

    @Test
    void loadsTheSingleVersionedUtf8PromptResource() {
        assertThat(SegmentPromptContract.VERSION).isEqualTo("segment-intent-prompt/22");
        assertThat(SegmentPromptContract.TEXT)
                .startsWith("Return exactly one raw JSON object")
                .contains("Return one formal Segment Intent v1.3 JSON object")
                .contains("For ORDERED_ENUM, preserve the user's ordering operator")
                .contains("Every DATE or TIMESTAMP ATTRIBUTE range must be lower-inclusive and upper-exclusive")
                .contains("never replace a unit-price comparison with a product identity field")
                .contains("AMBIGUOUS_FILTER_SCOPE")
                .contains("MISSING_METADATA_DEFINITION")
                .contains("every distinct-dimension request uses COUNT_DISTINCT")
                .doesNotContain("version: segment-intent-prompt/22");
        assertThat(SegmentPromptContract.HASH.value()).isNotBlank();
    }

    @Test
    void normalizesWindowsLineEndingsBeforeParsing() {
        var contract = SegmentPromptContract.parse(
                "version: segment-intent-prompt/7\r\n\r\nFirst line\r\nSecond line\r\n");

        assertThat(contract.version()).isEqualTo("segment-intent-prompt/7");
        assertThat(contract.text()).isEqualTo("First line\nSecond line");
    }

    @Test
    void rejectsMissingOrInvalidVersionHeadersAndEmptyBodies() {
        assertThatThrownBy(() -> SegmentPromptContract.parse("prompt only"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version header");
        assertThatThrownBy(() -> SegmentPromptContract.parse("version: custom/1\n\nprompt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version is invalid");
        assertThatThrownBy(() -> SegmentPromptContract.parse("version: segment-intent-prompt/1\n\n   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("text is empty");
    }

    @Test
    void loadsTheStagedSemanticDraftPromptFromItsOwnVersionedResource() {
        assertThat(SemanticDraftPromptContract.VERSION)
                .isEqualTo("segment-semantic-draft-prompt/2");
        assertThat(SemanticDraftPromptContract.TEXT)
                .contains("Every semantic value is represented by a slot")
                .contains("Stage behavior is supplied after this contract");
    }
}
