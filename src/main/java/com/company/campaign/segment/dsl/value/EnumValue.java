package com.company.campaign.segment.dsl.value;

/**
 * A bounded uppercase machine code naming an enum member, for example {@code GOLD}.
 *
 * <p>Membership of the code in a definition's declared enum values is a T04/T07 concern; T05 only fixes the
 * lexical shape so a display label such as {@code Gold} cannot masquerade as a machine code.
 */
public record EnumValue(String value) implements ScalarValue {

    public EnumValue {
        value = ValueTexts.requireMachineCode(value, "value");
    }

    @Override
    public ValueKind kind() {
        return ValueKind.ENUM;
    }
}