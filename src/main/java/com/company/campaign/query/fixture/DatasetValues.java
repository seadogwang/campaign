package com.company.campaign.query.fixture;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The one closed accessor from a {@link DatasetField} to a {@link DatasetRow} component.
 *
 * <p>Every read goes through this exhaustive {@code switch}, so a caller cannot reach a payload field the
 * mapping never named and a field/row mismatch is a compile-time and run-time impossibility rather than a
 * stringly-typed lookup. A field that does not belong to the row's entity yields {@code null} rather than
 * silently reading an unrelated component.
 */
final class DatasetValues {

    /** The value of one field on one row, or {@code null} when the field is absent or SQL NULL. */
    static Object value(DatasetRow row, DatasetField field) {
        if (row == null || field == null || field.entity() != row.entity()) {
            return null;
        }
        return switch (row) {
            case DatasetRow.MemberRow member -> memberValue(member, field);
            case DatasetRow.OrderRow order -> orderValue(order, field);
            case DatasetRow.EventRow event -> eventValue(event, field);
        };
    }

    private static Object memberValue(DatasetRow.MemberRow member, DatasetField field) {
        return switch (field) {
            case MEMBER_ID -> member.memberId();
            case MEMBER_TIER -> member.tier();
            case MEMBER_STATUS -> member.status();
            case MEMBER_CITY -> member.city();
            case MEMBER_JOIN_DATE -> member.joinDate();
            case MEMBER_NAME -> member.name();
            case MEMBER_AGE -> member.age() == null ? null : BigDecimal.valueOf(member.age());
            case MEMBER_SCORE -> member.score();
            case MEMBER_FLAG -> member.flag();
            case MEMBER_LAST_SEEN -> member.lastSeen();
            case MEMBER_BALANCE -> member.balance();
            case MEMBER_BALANCE_CURRENCY -> member.balanceCurrency();
            default -> null;
        };
    }

    private static Object orderValue(DatasetRow.OrderRow order, DatasetField field) {
        return switch (field) {
            case ORDER_ID -> order.orderId();
            case ORDER_MEMBER_ID -> order.memberId();
            case ORDER_AMOUNT -> order.amount();
            case ORDER_CURRENCY -> order.currency();
            case ORDER_PAID_AT -> order.paidAt();
            case ORDER_REFUNDED -> order.refunded();
            case ORDER_STATUS -> order.status();
            case ORDER_CHANNEL -> order.channel();
            default -> null;
        };
    }

    private static Object eventValue(DatasetRow.EventRow event, DatasetField field) {
        return switch (field) {
            case EVENT_ID -> event.eventId();
            case EVENT_MEMBER_ID -> event.memberId();
            case EVENT_OCCURRED_AT -> event.occurredAt();
            case EVENT_TYPE -> event.type();
            default -> null;
        };
    }

    /** The string form of a field used as a relation join key; {@code null} when the value is SQL NULL. */
    static String joinKey(DatasetRow row, DatasetField field) {
        Object value = value(row, field);
        return value instanceof String text ? text : null;
    }

    /** The exact decimal form of a numeric field, or {@code null} when the value is SQL NULL. */
    static BigDecimal decimal(DatasetRow row, DatasetField field) {
        Object value = value(row, field);
        return value instanceof BigDecimal decimal ? decimal : null;
    }

    /** The instant form of a timestamp field, or {@code null} when the value is SQL NULL. */
    static Instant instant(DatasetRow row, DatasetField field) {
        Object value = value(row, field);
        return value instanceof Instant instant ? instant : null;
    }

    private DatasetValues() {
    }
}