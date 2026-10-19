package com.company.campaign.contracts;

/**
 * Bean Validation patterns shared by the base contracts.
 *
 * <p>They are collected here so that a reference identifier accepted by one contract is accepted by all
 * of them; drifting per-record regexes would let an identifier exist in one place and be invalid in
 * another.
 */
public final class ContractPatterns {

    /** Identifier of a tenant, program, user, DataScope, snapshot, plan, agent run or conversation. */
    public static final String REFERENCE_ID = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$";

    /** Stable uppercase code identifying a kind of immutable snapshot. */
    public static final String SNAPSHOT_KIND = "^[A-Z][A-Z0-9_]{0,63}$";

    /** Bounded uppercase machine code, such as an audit reason code. */
    public static final String MACHINE_CODE = "^[A-Z][A-Z0-9_]{0,63}$";

    /** ISO-4217 reporting currency. */
    public static final String CURRENCY = "^[A-Z]{3}$";

    private ContractPatterns() {
    }
}