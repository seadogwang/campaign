package com.company.campaign.segment.dsl;

import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.testsupport.SegmentDslFixtures;
import com.company.campaign.segment.dsl.value.TypedValue;
import com.company.campaign.segment.dsl.window.TimeWindow;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Strict round-trip and deterministic canonical bytes for every T05 subtype. */
class SegmentDslCodecRoundTripTest {

    private final SegmentDslCodec codec = new SegmentDslCodec();

    @Test
    void roundTripsAndCanonicalizesEveryTypedValueSubtype() {
        for (TypedValue value : SegmentDslFixtures.everyValue()) {
            String first = codec.writeValueString(value);
            String second = codec.writeValueString(codec.readValue(first));
            assertThat(second).as("canonical bytes for %s", value).isEqualTo(first);
            assertThat(codec.readValue(first)).isEqualTo(value);
        }
    }

    @Test
    void roundTripsAndCanonicalizesEveryTimeWindowSubtype() {
        for (TimeWindow window : SegmentDslFixtures.everyWindow()) {
            String first = codec.writeWindowString(window);
            String second = codec.writeWindowString(codec.readWindow(first));
            assertThat(second).as("canonical bytes for %s", window).isEqualTo(first);
            assertThat(codec.readWindow(first)).isEqualTo(window);
        }
    }

    @Test
    void roundTripsEveryPredicateSubtype() {
        Predicate predicate = SegmentDslFixtures.richPredicate();
        String json = codec.writePredicateString(predicate);
        assertThat(codec.readPredicate(json)).isEqualTo(predicate);
        assertThat(codec.writePredicateString(codec.readPredicate(json))).isEqualTo(json);
    }

    @Test
    void roundTripsTheWholeDocumentWithStableCanonicalBytes() {
        SegmentDsl document = SegmentDslFixtures.document();
        byte[] bytes = codec.write(document);
        SegmentDsl reparsed = codec.parse(bytes);

        assertThat(reparsed).isEqualTo(document);
        assertThat(codec.write(reparsed)).isEqualTo(bytes);
        assertThat(codec.writeString(reparsed)).isEqualTo(new String(bytes, StandardCharsets.UTF_8));

        // Canonical output sorts object keys and is compact UTF-8 with no insignificant whitespace.
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertThat(json).startsWith("{\"editableDslHash\":");
        assertThat(json).doesNotContain("\n").doesNotContain(": ");
    }

    @Test
    void preservesEditableChildOrderNodeIdsAndLexicalDecimalScale() {
        String json = codec.writeString(SegmentDslFixtures.document());
        assertThat(json).contains("\"amount\":\"1000.00\"");
        assertThat(json).contains("\"value\":\"500.5\"");
        assertThat(json).contains("\"nodeId\":\"n-tier-in\"");
        // Child order is preserved exactly as declared, never sorted.
        assertThat(json.indexOf("n-tier-in")).isLessThan(json.indexOf("n-tier-null"));
        assertThat(json.indexOf("n-order-1")).isLessThan(json.indexOf("n-order-2"));
    }

    @Test
    void parseVerifiesTheDeclaredEditableHash() {
        SegmentDsl document = SegmentDslFixtures.document();
        SegmentDsl parsed = codec.parse(codec.writeString(document));
        assertThat(parsed.editableDslHash()).isEqualTo(document.editableDslHash());
        assertThat(parsed.editableDslHash()).isEqualTo(
                com.company.campaign.segment.dsl.codec.SegmentDslHasher.hash(document.hashMaterial()));
    }
}