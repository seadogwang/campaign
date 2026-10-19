package com.company.campaign.segment.dsl;

import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslLimits;
import com.company.campaign.segment.dsl.window.FixedWindow;
import com.company.campaign.segment.dsl.window.LifetimeWindow;
import com.company.campaign.segment.dsl.window.RollingWindow;
import com.company.campaign.segment.dsl.window.RelativeToAttributeWindow;
import com.company.campaign.segment.dsl.window.RelativeToEventWindow;
import com.company.campaign.segment.dsl.window.SelectedEventWindow;
import com.company.campaign.segment.dsl.window.TimeWindow;
import com.company.campaign.segment.dsl.window.WindowType;
import com.company.campaign.segment.dsl.window.WindowUnit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.company.campaign.testsupport.ContractAssertions.assertFailsWithCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T05 representation rules for the closed {@code TimeWindow} union. Boundary derivation against {@code asOf}
 * and trusted timezone policy belong to T07, so no clock or default timezone may be consulted here.
 */
class SegmentDslTimeWindowTest {

    @Test
    void everyWindowReportsItsClosedDiscriminator() {
        assertThat(new RollingWindow(30, WindowUnit.DAY).type()).isEqualTo(WindowType.ROLLING);
        assertThat(new LifetimeWindow().type()).isEqualTo(WindowType.LIFETIME);
        assertThat(new FixedWindow("2026-01-01T00:00:00+08:00", "2026-02-01T00:00:00+08:00").type())
                .isEqualTo(WindowType.FIXED);
        assertThat(new RelativeToAttributeWindow("member.first_order_at", 0, 30, WindowUnit.DAY).type())
                .isEqualTo(WindowType.RELATIVE_TO_ATTRIBUTE);
        assertThat(new SelectedEventWindow("order.first_positive", List.of()).type())
                .isEqualTo(WindowType.SELECTED_EVENT);
        assertThat(new RelativeToEventWindow(
                "order.first_positive", List.of(), 0, 180, WindowUnit.DAY, true).type())
                .isEqualTo(WindowType.RELATIVE_TO_EVENT);
        assertThat(WindowType.values()).hasSize(6);
        assertThat(WindowUnit.values()).containsExactly(WindowUnit.DAY, WindowUnit.WEEK);
    }

    @Test
    void rollingAcceptsOnlyDayAndWeekPositiveBoundedAmounts() {
        assertThat(new RollingWindow(1, WindowUnit.DAY).amount()).isEqualTo(1);
        assertThat(new RollingWindow(SegmentDslLimits.MAX_WINDOW_AMOUNT, WindowUnit.WEEK).unit())
                .isEqualTo(WindowUnit.WEEK);
        assertFailsWithCode(() -> new RollingWindow(0, WindowUnit.DAY), SegmentDslErrorCodes.TIME_WINDOW_INVALID);
        assertFailsWithCode(() -> new RollingWindow(-1, WindowUnit.DAY), SegmentDslErrorCodes.TIME_WINDOW_INVALID);
        assertFailsWithCode(
                () -> new RollingWindow(SegmentDslLimits.MAX_WINDOW_AMOUNT + 1, WindowUnit.DAY),
                SegmentDslErrorCodes.LIMIT_EXCEEDED);
    }

    @Test
    void rollingUnitVocabularyIsClosedToMonthAndYear() {
        // MONTH/YEAR exist in metadata window capabilities but are not valid Phase A ROLLING units.
        assertFailsWithCode(() -> WindowUnit.fromWire("MONTH"), SegmentDslErrorCodes.TIME_WINDOW_INVALID);
        assertFailsWithCode(() -> WindowUnit.fromWire("YEAR"), SegmentDslErrorCodes.TIME_WINDOW_INVALID);
        assertFailsWithCode(() -> WindowUnit.fromWire("day"), SegmentDslErrorCodes.TIME_WINDOW_INVALID);
        assertFailsWithCode(() -> WindowUnit.fromWire("HOUR"), SegmentDslErrorCodes.TIME_WINDOW_INVALID);
        assertThat(WindowUnit.fromWire("DAY")).isEqualTo(WindowUnit.DAY);
        assertThat(WindowUnit.fromWire("WEEK")).isEqualTo(WindowUnit.WEEK);
    }

    @Test
    void lifetimeCarriesNoBoundaryFields() {
        assertThat(new LifetimeWindow().type()).isEqualTo(WindowType.LIFETIME);
        assertThat(new LifetimeWindow()).isEqualTo(new LifetimeWindow());
    }

    @Test
    void fixedRequiresOffsetsAndStrictInstantOrdering() {
        FixedWindow window = new FixedWindow("2026-01-01T00:00:00+08:00", "2026-02-01T00:00:00+08:00");
        assertThat(window.start()).isEqualTo("2026-01-01T00:00:00+08:00");
        assertThat(window.end()).isEqualTo("2026-02-01T00:00:00+08:00");

        // The same instant expressed with a different offset is still a valid, ordered boundary pair.
        assertThat(new FixedWindow("2026-01-01T00:00:00+08:00", "2025-12-31T16:00:01Z").end())
                .isEqualTo("2025-12-31T16:00:01Z");
    }

    @Test
    void fixedRejectsOffsetFreeReversedEqualAndMalformedBoundaries() {
        assertFailsWithCode(
                () -> new FixedWindow("2026-01-01T00:00:00", "2026-02-01T00:00:00Z"),
                SegmentDslErrorCodes.TIME_WINDOW_INVALID);
        assertFailsWithCode(
                () -> new FixedWindow("2026-02-01T00:00:00Z", "2026-01-01T00:00:00Z"),
                SegmentDslErrorCodes.TIME_WINDOW_INVALID);
        assertFailsWithCode(
                () -> new FixedWindow("2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"),
                SegmentDslErrorCodes.TIME_WINDOW_INVALID);
        assertFailsWithCode(
                () -> new FixedWindow("2026-01-01T00:00:00Z", null),
                SegmentDslErrorCodes.TIME_WINDOW_INVALID);
        assertFailsWithCode(
                () -> new FixedWindow(null, "2026-01-01T00:00:00Z"),
                SegmentDslErrorCodes.TIME_WINDOW_INVALID);
    }

    @Test
    void fixedRejectsLexicalVariantsOutsideTheSharedExactGrammar() {
        for (String rejected : List.of(
                "2026-01-01T00:00+08:00",                 // optional seconds
                "2026-01-01T00:00:00+0800",               // compact offset
                "2026-01-01T00:00:00",                    // offset-free
                "2026-01-01T00:00:00[Asia/Shanghai]",     // zone ID
                "2026-01-01t00:00:00z",                   // lowercase t/z
                "2026-01-01T00:00:00Z ",                  // trailing text
                "2026-01-01T00:00:00.1234567890Z",        // fraction beyond the precision bound
                "2026-02-30T00:00:00Z")) {                // invalid calendar day
            assertFailsWithCode(
                    () -> new FixedWindow(rejected, "2026-02-01T00:00:00Z"),
                    SegmentDslErrorCodes.TIME_WINDOW_INVALID);
        }

        // Fractional seconds on the boundary keep their exact lexeme.
        assertThat(new FixedWindow("2026-01-01T00:00:00.250Z", "2026-02-01T00:00:00+08:00").start())
                .isEqualTo("2026-01-01T00:00:00.250Z");
    }

    @Test
    void windowUnionIsClosed() {
        assertThat(TimeWindow.class.getPermittedSubclasses()).hasSize(6);
        List<TimeWindow> windows = List.of(
                new RollingWindow(30, WindowUnit.DAY), new LifetimeWindow(),
                new FixedWindow("2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"),
                new RelativeToAttributeWindow("member.first_order_at", 0, 30, WindowUnit.DAY),
                new SelectedEventWindow("order.first_positive", List.of()),
                new RelativeToEventWindow(
                        "order.first_positive", List.of(), 0, 180, WindowUnit.DAY, true));
        assertThat(windows).hasSize(6);
    }
}
