package com.company.campaign.segment.dsl;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.MetricPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.predicate.PredicateType;
import com.company.campaign.segment.dsl.testsupport.SegmentDslFixtures;
import com.company.campaign.segment.dsl.value.DecimalValue;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.value.IntegerValue;
import com.company.campaign.segment.dsl.window.LifetimeWindow;
import com.company.campaign.segment.dsl.window.RollingWindow;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static com.company.campaign.testsupport.ContractAssertions.assertFailsWithCode;
import static org.assertj.core.api.Assertions.assertThat;

/** Publish/verify behavior of {@code editableDslHash}, its full mutation surface and its reproducibility. */
class SegmentDslEditableHashTest {

    private final SegmentDslCodec codec = new SegmentDslCodec();

    private static AttributePredicate attribute(String nodeId, String attributeId, ComparisonOperator operator,
                                               com.company.campaign.segment.dsl.value.TypedValue value) {
        return new AttributePredicate(nodeId, attributeId, operator, ConditionValue.of(value));
    }

    private static Predicate pair(AttributePredicate first, AttributePredicate second) {
        return new GroupPredicate("n-root", PredicateType.ALL, List.of(first, second));
    }

    private static ContentHash hash(Predicate predicate) {
        return SegmentDslFixtures.publish(predicate).editableDslHash();
    }

    private static ContentHash hash(SubjectKind subject, Predicate predicate) {
        return SegmentDslFixtures.publish(subject, predicate).editableDslHash();
    }

    private static final AttributePredicate A =
            attribute("n-a", "member.tier", ComparisonOperator.EQUAL, new EnumValue("GOLD"));
    private static final AttributePredicate B =
            attribute("n-b", "member.order_count", ComparisonOperator.EQUAL, new IntegerValue(1L));

    @Test
    void publishComputesTheHashAndTheConstructorAndParserVerifyIt() {
        SegmentDsl document = SegmentDslFixtures.document();
        ContentHash expected = com.company.campaign.segment.dsl.codec.SegmentDslHasher.hash(document.hashMaterial());
        assertThat(document.editableDslHash()).isEqualTo(expected);
        assertThat(codec.parse(codec.writeString(document)).editableDslHash()).isEqualTo(expected);

        // A declared hash that is not the canonical hash of the material is refused, not trusted.
        assertFailsWithCode(
                () -> new SegmentDsl(
                        SegmentDslFixtures.DSL_VERSION, SubjectKind.MEMBER, SegmentDslFixtures.binding(),
                        document.predicate(), new ContentHash(SegmentDslFixtures.PLACEHOLDER_HASH)),
                SegmentDslErrorCodes.EDITABLE_HASH_MISMATCH);
    }

    @Test
    void parseRejectsATamperedDeclaredHash() {
        String valid = codec.writeString(SegmentDslFixtures.document());
        String tampered = valid.replace(
                SegmentDslFixtures.document().editableDslHash().value(), SegmentDslFixtures.PLACEHOLDER_HASH);
        assertThat(tampered).isNotEqualTo(valid);
        assertFailsWithCode(() -> codec.parse(tampered), SegmentDslErrorCodes.EDITABLE_HASH_MISMATCH);
    }

    @Test
    void everyEditableRootAndBindingFieldChangesTheHash() {
        ContentHash base = hash(pair(A, B));
        assertThat(hash(SubjectKind.ORDER, pair(A, B))).as("root subject").isNotEqualTo(base);
        assertThat(SegmentDslFixtures.publish(SegmentDslFixtures.binding("metadata-18"), pair(A, B))
                .editableDslHash()).as("snapshot ID pin").isNotEqualTo(base);
    }

    @Test
    void everyEditableNodeFieldChangesTheHash() {
        ContentHash base = hash(pair(A, B));
        assertThat(hash(pair(attribute("n-a2", "member.tier", ComparisonOperator.EQUAL, new EnumValue("GOLD")), B)))
                .as("node ID").isNotEqualTo(base);
        assertThat(hash(pair(attribute("n-a", "member.tier2", ComparisonOperator.EQUAL, new EnumValue("GOLD")), B)))
                .as("attribute reference").isNotEqualTo(base);
        assertThat(hash(pair(attribute("n-a", "member.tier", ComparisonOperator.NOT_EQUAL, new EnumValue("GOLD")), B)))
                .as("operator").isNotEqualTo(base);
        assertThat(hash(pair(attribute("n-a", "member.tier", ComparisonOperator.EQUAL, new EnumValue("SILVER")), B)))
                .as("enum operand").isNotEqualTo(base);
        assertThat(hash(pair(attribute("n-b", "member.order_count", ComparisonOperator.EQUAL, new IntegerValue(2L)),
                A)))
                .as("integer operand and child order").isNotEqualTo(base);
        assertThat(hash(new GroupPredicate("n-root", PredicateType.ANY, List.of(A, B))))
                .as("group discriminator").isNotEqualTo(base);
    }

    @Test
    void childOrderAndExtraChildrenChangeTheHash() {
        ContentHash base = hash(pair(A, B));
        assertThat(hash(pair(B, A))).as("declared child order").isNotEqualTo(base);
        assertThat(hash(new GroupPredicate("n-root", PredicateType.ALL, List.of(A, B,
                attribute("n-c", "member.tier", ComparisonOperator.EQUAL, new EnumValue("GOLD"))))))
                .as("child count").isNotEqualTo(base);
    }

    @Test
    void valueKindAndLexicalScaleChangeTheHash() {
        ContentHash integer = hash(attribute("n-v", "member.order_count", ComparisonOperator.EQUAL, new IntegerValue(1L)));
        ContentHash decimal = hash(attribute("n-v", "member.order_count", ComparisonOperator.EQUAL, new DecimalValue("1")));
        assertThat(decimal).as("value kind").isNotEqualTo(integer);

        // T07 owns decimal normalization, so 1000.00 and 1000.0 are distinct editable documents in T05.
        ContentHash scaled = hash(attribute("n-v", "member.x", ComparisonOperator.EQUAL, new DecimalValue("1000.00")));
        ContentHash rescaled = hash(attribute("n-v", "member.x", ComparisonOperator.EQUAL, new DecimalValue("1000.0")));
        assertThat(rescaled).as("lexical scale").isNotEqualTo(scaled);
    }

    @Test
    void everyEditableWindowFieldChangesTheHash() {
        Predicate day = new MetricPredicate(
                "n-m", "order.count", new RollingWindow(30, WindowUnit.DAY),
                ComparisonOperator.GREATER_THAN, ConditionValue.of(new IntegerValue(1L)));
        Predicate week = new MetricPredicate(
                "n-m", "order.count", new RollingWindow(30, WindowUnit.WEEK),
                ComparisonOperator.GREATER_THAN, ConditionValue.of(new IntegerValue(1L)));
        Predicate days7 = new MetricPredicate(
                "n-m", "order.count", new RollingWindow(7, WindowUnit.DAY),
                ComparisonOperator.GREATER_THAN, ConditionValue.of(new IntegerValue(1L)));
        Predicate lifetime = new MetricPredicate(
                "n-m", "order.count", new LifetimeWindow(),
                ComparisonOperator.GREATER_THAN, ConditionValue.of(new IntegerValue(1L)));

        assertThat(hash(week)).as("window unit").isNotEqualTo(hash(day));
        assertThat(hash(days7)).as("window amount").isNotEqualTo(hash(day));
        assertThat(hash(lifetime)).as("window type").isNotEqualTo(hash(day));
    }

    @Test
    void hashIsReproducibleAcrossDefaultLocaleAndTimezone() {
        Locale originalLocale = Locale.getDefault();
        TimeZone originalZone = TimeZone.getDefault();
        ContentHash baseline = hash(pair(A, B));
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
            ContentHash first = hash(pair(A, B));
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            TimeZone.setDefault(TimeZone.getTimeZone("America/Anchorage"));
            ContentHash second = hash(pair(A, B));
            assertThat(first).isEqualTo(baseline);
            assertThat(second).isEqualTo(baseline);
            assertThat(codec.writeString(SegmentDslFixtures.publish(pair(A, B))))
                    .isEqualTo(codec.writeString(SegmentDslFixtures.publish(pair(A, B))));
        } finally {
            Locale.setDefault(originalLocale);
            TimeZone.setDefault(originalZone);
        }
    }

    @Test
    void hashIsIndependentOfSourceCollectionMutability() {
        List<Predicate> source = new ArrayList<>(List.of(A, B));
        Predicate group = new GroupPredicate("n-root", PredicateType.ALL, source);
        ContentHash before = hash(group);
        source.add(attribute("n-c", "member.tier", ComparisonOperator.EQUAL, new EnumValue("GOLD")));
        ContentHash after = hash(group);
        assertThat(after).isEqualTo(before);
    }
}