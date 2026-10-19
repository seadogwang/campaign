package com.company.campaign.query.fixture;

import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.SubjectKind;

/**
 * The closed set of dataset fields a server-owned fixture mapping may reference.
 *
 * <p>It is the only way a plan dependency can be bound to fixture data: there is no arbitrary JSON path, no
 * reflection and no caller-supplied accessor anywhere in this package, so a mapping can only ever name one of
 * these constants and a row can only ever be read through a closed {@code switch} over them.
 *
 * <p>Each field declares the subject entity whose row carries it and the metadata data type it represents,
 * so a mapping that names an {@code ORDER} field for a {@code MEMBER} definition (or a {@code DATE} field
 * for an {@code INTEGER} definition) fails closed before any member is evaluated.
 *
 * <p>The required fields mirror the reviewed corpus-v1 payload shape exactly; the optional fields are the
 * declared closed extension that lets an attribute of every accepted metadata type be evaluated without
 * inventing an accessor.
 */
public enum DatasetField {

    // ---- members ---------------------------------------------------------------------------------
    MEMBER_ID(SubjectKind.MEMBER, SemanticDataType.STRING, true),
    MEMBER_TIER(SubjectKind.MEMBER, SemanticDataType.ENUM, true),
    MEMBER_STATUS(SubjectKind.MEMBER, SemanticDataType.ENUM, true),
    MEMBER_CITY(SubjectKind.MEMBER, SemanticDataType.STRING, true),
    MEMBER_JOIN_DATE(SubjectKind.MEMBER, SemanticDataType.DATE, true),
    MEMBER_NAME(SubjectKind.MEMBER, SemanticDataType.STRING, false),
    MEMBER_AGE(SubjectKind.MEMBER, SemanticDataType.INTEGER, false),
    MEMBER_SCORE(SubjectKind.MEMBER, SemanticDataType.DECIMAL, false),
    MEMBER_FLAG(SubjectKind.MEMBER, SemanticDataType.BOOLEAN, false),
    MEMBER_LAST_SEEN(SubjectKind.MEMBER, SemanticDataType.TIMESTAMP, false),
    MEMBER_BALANCE(SubjectKind.MEMBER, SemanticDataType.MONEY, false),
    MEMBER_BALANCE_CURRENCY(SubjectKind.MEMBER, SemanticDataType.STRING, false),

    // ---- orders ----------------------------------------------------------------------------------
    ORDER_ID(SubjectKind.ORDER, SemanticDataType.STRING, true),
    ORDER_MEMBER_ID(SubjectKind.ORDER, SemanticDataType.STRING, true),
    ORDER_AMOUNT(SubjectKind.ORDER, SemanticDataType.MONEY, true),
    ORDER_CURRENCY(SubjectKind.ORDER, SemanticDataType.STRING, true),
    ORDER_PAID_AT(SubjectKind.ORDER, SemanticDataType.TIMESTAMP, true),
    ORDER_REFUNDED(SubjectKind.ORDER, SemanticDataType.MONEY, true),
    ORDER_STATUS(SubjectKind.ORDER, SemanticDataType.ENUM, true),
    ORDER_CHANNEL(SubjectKind.ORDER, SemanticDataType.ENUM, false),

    // ---- events ----------------------------------------------------------------------------------
    EVENT_ID(SubjectKind.EVENT, SemanticDataType.STRING, true),
    EVENT_MEMBER_ID(SubjectKind.EVENT, SemanticDataType.STRING, true),
    EVENT_OCCURRED_AT(SubjectKind.EVENT, SemanticDataType.TIMESTAMP, true),
    EVENT_TYPE(SubjectKind.EVENT, SemanticDataType.ENUM, true),

    // ---- refunds ---------------------------------------------------------------------------------
    /** A refund row's referenced order id. Refunds belong to the order entity they settle. */
    REFUND_ORDER_ID(SubjectKind.ORDER, SemanticDataType.STRING, true),
    /** A refund row's amount, in the referenced order's currency. */
    REFUND_AMOUNT(SubjectKind.ORDER, SemanticDataType.MONEY, true);

    private final SubjectKind entity;
    private final SemanticDataType dataType;
    private final boolean required;

    DatasetField(SubjectKind entity, SemanticDataType dataType, boolean required) {
        this.entity = entity;
        this.dataType = dataType;
        this.required = required;
    }

    /** The subject entity whose row carries this field. */
    public SubjectKind entity() {
        return entity;
    }

    /** The metadata data type this field represents. */
    public SemanticDataType dataType() {
        return dataType;
    }

    /** True when every row of this field's entity must declare the field (a JSON null is still a value). */
    public boolean required() {
        return required;
    }

    /**
     * The closed dataset field that carries this monetary value's own currency, or {@code null} when this
     * field is not monetary.
     *
     * <p>The pairing is server-owned and exhaustive, so a conversion can never guess a currency or fall back
     * to the reporting currency for a row whose own currency is unknown.
     */
    public DatasetField currencyField() {
        return switch (this) {
            case ORDER_AMOUNT, ORDER_REFUNDED -> ORDER_CURRENCY;
            case MEMBER_BALANCE -> MEMBER_BALANCE_CURRENCY;
            default -> null;
        };
    }
}