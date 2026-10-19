package com.company.campaign.query.fixture;

import java.math.BigDecimal;

/**
 * One immutable refund row, keyed by the order it settles.
 *
 * <p>The amount is an exact decimal in the referenced order's currency; refunds are never converted
 * independently because a refund can only settle the amount that was paid.
 */
public record RefundRow(String orderId, BigDecimal amount) {
}