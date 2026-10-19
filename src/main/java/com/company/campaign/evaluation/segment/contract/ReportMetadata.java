package com.company.campaign.evaluation.segment.contract;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.SchemaVersion;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Pattern;

/**
 * The reproducibility header of an evaluation report.
 *
 * <p>Segment specification section 24 lists what every run must freeze: model, provider and version;
 * decoding parameters; prompt, Skill and Tool schema hashes; resolver and canonicalizer versions; Metadata,
 * corpus and fixture versions; {@code asOf}, timezone and watermarks; code revision and environment. All
 * of them are reserved here so a report written by T13 has a reviewed shape to write into rather than
 * inventing one.
 *
 * <p>T03 defines the format only. It runs no model, computes no metric and produces no verdict: a
 * {@link GateOutcome} field exists so a report can state one, not so this task can claim one.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportMetadata(
        SchemaVersion schemaVersion,
        String reportId,
        GateOutcome gateOutcome,
        ReportModelDescriptor model,
        JsonNode decodingParameters,
        ReportArtifactHashes artifactHashes,
        ReportVersionPins versionPins,
        ReportTimeContext timeContext,
        String codeRevision,
        String environment
) {
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern REVISION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$");

    public ReportMetadata {
        if (schemaVersion == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID, "Report metadata must declare a schema version");
        }
        if (reportId == null || !REFERENCE_ID.matcher(reportId).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Report id '" + reportId + "' is not a valid reference identifier");
        }
        if (gateOutcome == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID, "Report '" + reportId + "' must declare a gate outcome");
        }
        if (model == null || artifactHashes == null || versionPins == null || timeContext == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "Report '" + reportId + "' must declare model, artifact hashes, version pins and time context");
        }
        if (codeRevision == null || !REVISION.matcher(codeRevision).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Report code revision '" + codeRevision + "' is outside its bounded grammar");
        }
        if (environment == null || !REFERENCE_ID.matcher(environment).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Report environment '" + environment + "' is not a valid reference identifier");
        }
    }
}