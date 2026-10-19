package com.company.campaign.segment.dsl.value;

/**
 * A monetary amount in a declared ISO-4217 currency.
 *
 * <p>The amount is a plain-decimal lexeme with its declared scale preserved and the currency reuses the T01
 * three-letter uppercase grammar. Conversion to a reporting currency is a T07/T08 decision and is never
 * applied here.
 */
public record MoneyValue(String amount, String currency) implements ScalarValue {

    public MoneyValue {
        amount = ValueTexts.requireDecimal(amount, "amount");
        currency = ValueTexts.requireCurrency(currency, "currency");
    }

    @Override
    public ValueKind kind() {
        return ValueKind.MONEY;
    }
}