package com.company.campaign.builder.agent;

import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.NoValueCondition;
import com.company.campaign.segment.dsl.predicate.TypedValueCondition;
import com.company.campaign.segment.dsl.value.BooleanValue;
import com.company.campaign.segment.dsl.value.DateValue;
import com.company.campaign.segment.dsl.value.DateTimeValue;
import com.company.campaign.segment.dsl.value.DecimalValue;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.value.IntegerValue;
import com.company.campaign.segment.dsl.value.ListValue;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.dsl.value.RangeValue;
import com.company.campaign.segment.dsl.value.ScalarValue;
import com.company.campaign.segment.dsl.value.StringValue;
import com.company.campaign.segment.dsl.value.TypedValue;
import com.company.campaign.segment.dsl.window.FixedWindow;
import com.company.campaign.segment.dsl.window.LifetimeWindow;
import com.company.campaign.segment.dsl.window.RelativeToAttributeWindow;
import com.company.campaign.segment.dsl.window.RelativeToEventWindow;
import com.company.campaign.segment.dsl.window.RollingWindow;
import com.company.campaign.segment.dsl.window.SelectedEventWindow;
import com.company.campaign.segment.dsl.window.TimeWindow;

/**
 * Deterministic, bounded semantic summaries of accepted DSL operands and windows.
 *
 * <p>A free-form STRING operand is summarized by kind only: a raw text operand may itself be an external
 * identifier or personal data, so it is deliberately never copied into an explanation claim. Enum codes,
 * numeric amounts, dates and money amounts are semantic facts the accepted document already publishes, so they
 * are summarized with their kind. Every summary is length-bounded.
 */
final class SegmentValueSummaries {

    private SegmentValueSummaries() {
    }

    /** A bounded semantic summary of one condition value. */
    static String condition(ConditionValue condition) {
        if (condition == null) {
            return null;
        }
        if (condition instanceof NoValueCondition) {
            return "NO_VALUE";
        }
        if (condition instanceof TypedValueCondition typed) {
            return typed(typed.value());
        }
        return "VALUE";
    }

    /** A bounded semantic summary of one typed value. */
    static String typed(TypedValue value) {
        if (value instanceof StringValue) {
            return "STRING";
        }
        if (value instanceof EnumValue enumValue) {
            return "ENUM:" + enumValue.value();
        }
        if (value instanceof IntegerValue integerValue) {
            return "INTEGER:" + integerValue.value();
        }
        if (value instanceof DecimalValue decimalValue) {
            return "DECIMAL:" + decimalValue.value();
        }
        if (value instanceof BooleanValue booleanValue) {
            return "BOOLEAN:" + booleanValue.value();
        }
        if (value instanceof DateValue dateValue) {
            return "DATE:" + dateValue.value();
        }
        if (value instanceof DateTimeValue dateTimeValue) {
            return "DATETIME:" + dateTimeValue.value();
        }
        if (value instanceof MoneyValue moneyValue) {
            return "MONEY:" + moneyValue.amount() + ":" + moneyValue.currency();
        }
        if (value instanceof ListValue listValue) {
            return "LIST(" + listValue.items().size() + ")";
        }
        if (value instanceof RangeValue rangeValue) {
            return "RANGE(" + scalar(rangeValue.lower()) + (rangeValue.includeLower() ? "[" : "(")
                    + ".." + scalar(rangeValue.upper()) + (rangeValue.includeUpper() ? "]" : ")");
        }
        return "VALUE";
    }

    /** A bounded semantic summary of one time window. */
    static String window(TimeWindow window) {
        if (window == null) {
            return null;
        }
        if (window instanceof RollingWindow rolling) {
            return "ROLLING:" + rolling.amount() + ":" + rolling.unit();
        }
        if (window instanceof LifetimeWindow) {
            return "LIFETIME";
        }
        if (window instanceof FixedWindow fixed) {
            return "FIXED:" + fixed.start() + ":" + fixed.end();
        }
        if (window instanceof RelativeToAttributeWindow relative) {
            return "RELATIVE_TO_ATTRIBUTE:" + relative.anchorDefinitionId() + ":" + relative.startOffset()
                    + ":" + relative.endOffset() + ":" + relative.unit();
        }
        if (window instanceof SelectedEventWindow selected) {
            return "SELECTED_EVENT:" + selected.selectorId();
        }
        if (window instanceof RelativeToEventWindow relative) {
            return "RELATIVE_TO_EVENT:" + relative.selectorId() + ":" + relative.startOffset()
                    + ":" + relative.endOffset() + ":" + relative.unit();
        }
        return "WINDOW";
    }

    private static String scalar(ScalarValue value) {
        return typed(value);
    }
}