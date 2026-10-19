package com.company.campaign.metadata.domain;

/**
 * Closed Unicode normalization policy for a {@code STRING} Attribute or Metric.
 *
 * <p>The policy is an explicit member of a closed enum, never a free-form algorithm name: an evaluator
 * either normalizes with one of the four standard Unicode forms or does not normalize at all, and the
 * chosen policy is pinned in the snapshot hash so two evaluators cannot silently differ.
 *
 * <p>This is the definition's declared comparison behavior. It is independent of
 * {@code MetadataTextNormalizer}, which stays the fixed NFKC/whitespace/{@code Locale.ROOT} normalizer for
 * catalog discovery and duplicate detection only.
 */
public enum StringNormalization {

    /** Values compare without any Unicode normalization. */
    NONE,

    /** Values compare after Unicode Normalization Form C (canonical composition). */
    NFC,

    /** Values compare after Unicode Normalization Form D (canonical decomposition). */
    NFD,

    /** Values compare after Unicode Normalization Form KC (compatibility composition). */
    NFKC,

    /** Values compare after Unicode Normalization Form KD (compatibility decomposition). */
    NFKD
}