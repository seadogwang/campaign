package com.company.campaign.metadata.domain;

/**
 * Closed locale policy for a {@code STRING} Attribute or Metric.
 *
 * <p>Locale behavior is stated explicitly: a definition is either locale-neutral — no locale-dependent
 * collation, casing or ordering may influence the comparison — or it pins one validated
 * {@link com.company.campaign.contracts.LocaleTag}. There is no implicit process default.
 */
public enum StringLocaleMode {

    /** Comparison may not depend on any locale; no locale tag is declared. */
    LOCALE_NEUTRAL,

    /** Comparison is defined for exactly one validated locale tag. */
    LOCALE_SPECIFIC
}