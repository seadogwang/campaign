package com.company.campaign.contracts;

import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Reference to an immutable snapshot of derived or external content.
 *
 * <p>A snapshot reference is the only way later contracts may point at content that is not the
 * authoritative Domain truth: the referenced content is identified by its {@link #contentHash() hash}, so
 * a reader can prove it received exactly the bytes that were published and cannot be handed a drifted
 * "current" value in place of a frozen one.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SnapshotRef(
        @NotBlank @Pattern(regexp = ContractPatterns.SNAPSHOT_KIND) String kind,
        @NotBlank @Pattern(regexp = ContractPatterns.REFERENCE_ID) String snapshotId,
        @NotNull @Valid SchemaVersion schemaVersion,
        @NotNull @Valid ContentHash contentHash
) {
}