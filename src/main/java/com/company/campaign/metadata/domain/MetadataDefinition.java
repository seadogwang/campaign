package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataKind;

import java.util.List;

/**
 * Common immutable view over an Attribute, Metric or Relation definition.
 *
 * <p>The T04 query service returns this view so list/get/search can authorize and order every definition
 * uniformly. Implementations are strict immutable records; exposing this interface never exposes a mutable
 * index or a partially validated definition.
 */
public interface MetadataDefinition {

    /** Stable definition ID, unique across the whole snapshot. */
    String id();

    /** The definition kind this implementation belongs to. */
    MetadataKind kind();

    /** Human-readable display name, preserved in its original text form. */
    String displayName();

    /** Normalized-order explanatory aliases; empty when none are declared. */
    List<String> aliases();
}