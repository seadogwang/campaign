package com.company.campaign.segment.dsl.value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Ordering that follows from the value's own representation, with no Metadata policy involved.
 *
 * <p>A RANGE bound pair is only compared when both bounds share a kind whose order is intrinsic to that
 * representation: integers, plain decimals, local dates, offset date-times and same-currency money. STRING
 * order depends on a collation chosen by Metadata, ENUM order depends on the definition's enum order,
 * BOOLEAN is unordered in Phase A, and cross-currency money has no order. Those cases, and every same-type
 * compatibility question, are deliberately left to T07, which returns {@link Optional#empty()} here.
 */
final class IntrinsicOrder {

    private IntrinsicOrder() {
    }

    /** Compares two same-kind scalar bounds when their representation has an intrinsic order. */
    static Optional<Integer> compare(ScalarValue lower, ScalarValue upper) {
        if (lower.kind() != upper.kind()) {
            return Optional.empty();
        }
        return switch (lower.kind()) {
            case INTEGER -> Optional.of(Long.compare(((IntegerValue) lower).value(), ((IntegerValue) upper).value()));
            case DECIMAL -> Optional.of(new BigDecimal(((DecimalValue) lower).value())
                    .compareTo(new BigDecimal(((DecimalValue) upper).value())));
            case DATE -> Optional.of(LocalDate.parse(((DateValue) lower).value())
                    .compareTo(LocalDate.parse(((DateValue) upper).value())));
            case DATETIME -> Optional.of(OffsetDateTime.parse(((DateTimeValue) lower).value()).toInstant()
                    .compareTo(OffsetDateTime.parse(((DateTimeValue) upper).value()).toInstant()));
            case MONEY -> moneyCompare((MoneyValue) lower, (MoneyValue) upper);
            case STRING, BOOLEAN, ENUM -> Optional.empty();
            case LIST, RANGE -> Optional.empty();
        };
    }

    private static Optional<Integer> moneyCompare(MoneyValue lower, MoneyValue upper) {
        if (!lower.currency().equals(upper.currency())) {
            return Optional.empty();
        }
        return Optional.of(new BigDecimal(lower.amount()).compareTo(new BigDecimal(upper.amount())));
    }
}