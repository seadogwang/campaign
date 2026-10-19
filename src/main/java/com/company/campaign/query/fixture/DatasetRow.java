package com.company.campaign.query.fixture;

import com.company.campaign.metadata.domain.SubjectKind;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One immutable deterministic dataset row.
 *
 * <p>A field that is absent from the payload or explicitly {@code null} is stored as {@code null} and means
 * SQL NULL: it is never replaced by a default, an empty string or zero. Every component is an immutable
 * value type, so a row can be read but never mutated, and no row carries a wall-clock value — every instant
 * was parsed from the pinned payload bytes.
 */
public sealed interface DatasetRow
        permits DatasetRow.MemberRow, DatasetRow.OrderRow, DatasetRow.EventRow {

    /** The subject entity this row belongs to. */
    SubjectKind entity();

    /** One member row. */
    record MemberRow(
            String memberId,
            String tier,
            String status,
            String city,
            String joinDate,
            String name,
            Integer age,
            BigDecimal score,
            Boolean flag,
            Instant lastSeen,
            BigDecimal balance,
            String balanceCurrency) implements DatasetRow {

        @Override
        public SubjectKind entity() {
            return SubjectKind.MEMBER;
        }
    }

    /** One order row. Amounts are exact decimals; the currency is the amount's own currency. */
    record OrderRow(
            String orderId,
            String memberId,
            BigDecimal amount,
            String currency,
            Instant paidAt,
            BigDecimal refunded,
            String status,
            String channel) implements DatasetRow {

        @Override
        public SubjectKind entity() {
            return SubjectKind.ORDER;
        }
    }

    /** One event row. */
    record EventRow(
            String eventId,
            String memberId,
            Instant occurredAt,
            String type) implements DatasetRow {

        @Override
        public SubjectKind entity() {
            return SubjectKind.EVENT;
        }
    }
}