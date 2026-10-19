package com.company.campaign.evaluation.segment.contract;

import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Hashes of the versioned artifacts that shaped a run: system prompt, Skill and Tool schema.
 *
 * <p>These are what make "the same model" mean something. Changing any of them changes the answer, so
 * Segment specification section 25 ties them to {@code EVALUATION_REQUIRED}. They are nullable on purpose:
 * a deterministic rule-based run has no prompt, Skill or Tool schema, and reporting a placeholder hash for
 * an artifact that does not exist would be worse than reporting its absence.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportArtifactHashes(
        ContentHash systemPromptHash,
        ContentHash skillHash,
        ContentHash toolSchemaHash
) {
}