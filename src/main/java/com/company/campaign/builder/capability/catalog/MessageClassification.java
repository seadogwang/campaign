package com.company.campaign.builder.capability.catalog;

/**
 * The closed set of classification codes a Message Template Version may declare.
 *
 * <p>It exists so a template can be labelled at the low, authoring-time classification level and no higher:
 * a template version is a frozen authoring artifact, never a production delivery, so a value such as
 * {@code CONFIDENTIAL} or {@code RESTRICTED} — a level only a reviewed production asset may claim — is
 * refused rather than silently accepted. An unknown token is refused rather than coerced into a neighbour.
 */
public enum MessageClassification {

    /** The template may be referenced from any authoring composition. */
    PUBLIC,

    /** The template is restricted to internal authoring composition. */
    INTERNAL;

    /**
     * Parses one canonical classification token, refusing anything this build does not declare.
     *
     * <p>The rejected token is never echoed: it is catalog content and must not become observable output.
     */
    public static MessageClassification parse(String token) {
        if (token != null) {
            for (MessageClassification classification : values()) {
                if (classification.name().equals(token)) {
                    return classification;
                }
            }
        }
        throw CatalogSafety.invalid("messageTemplateClassification");
    }
}