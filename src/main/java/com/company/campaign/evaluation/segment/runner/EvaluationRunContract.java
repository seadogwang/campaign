package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.evaluation.segment.review.CorpusUsageClassification;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The immutable T13 run contract, frozen before the first Case starts.
 *
 * <p>It is the whole of "the same frozen inputs": the run and report identity and schema version, the exact
 * provider/model/model version, the closed typed decoding parameters, the system-prompt, Skill and Tool-schema
 * content hashes (with honest absence where an artifact does not exist), the Intent parser/codec, resolver and
 * canonicalizer versions, the Metadata catalog and per-Case snapshot pins, the candidate-corpus pins, the
 * fixture catalog and the exact fixture payload/mapping/scope/FX pins the run will execute, the frozen time
 * context, the explicit code revision and bounded environment identifier, and the review classification with
 * its issue codes, review-set hash and manifest hash as obtained at run start.
 *
 * <p>Nothing in this contract is read from a wall clock, a random source, the default locale or timezone, the
 * ambient Git state or a provider default: every one of those is either an explicit request field or absent.
 */
public record EvaluationRunContract(
        String runContractVersion,
        String reportSchemaVersion,
        String reportId,
        ModelPin model,
        DecodingParameters decoding,
        ArtifactPins artifacts,
        ParserPins parsers,
        MetadataPins metadata,
        CandidateCorpusPins candidateCorpus,
        ReviewPins review,
        TimePins time,
        CodePins code,
        List<EvaluationCasePins> cases
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    /**
     * The bounded version-reference grammar: like a reference identifier, and additionally admitting the
     * {@code /} that every accepted version string such as {@code segment-canonicalizer/1.1} carries.
     */
    private static final String VERSION_REFERENCE = "^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$";

    public EvaluationRunContract {
        if (!T13Versions.RUN_CONTRACT_VERSION.equals(runContractVersion)
                || !T13Versions.REPORT_SCHEMA_VERSION.equals(reportSchemaVersion)) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.PIN_CONTRADICTORY,
                    "A run contract must declare the exact T13 contract versions");
        }
        if (reportId == null || !reportId.matches(ContractPatterns.REFERENCE_ID)) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.RUN_IDENTIFIER_INVALID, "A run contract requires a bounded report id");
        }
        if (model == null || decoding == null || artifacts == null || parsers == null || metadata == null
                || candidateCorpus == null || review == null || time == null || code == null) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.PIN_MISSING, "A run contract requires every pin group");
        }
        if (cases == null || cases.isEmpty()) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.PIN_MISSING, "A run contract requires the frozen Case pins");
        }
        cases = List.copyOf(new ArrayList<>(cases));
        Set<String> ids = new LinkedHashSet<>();
        for (EvaluationCasePins pins : cases) {
            if (!ids.add(pins.caseId())) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_CONTRADICTORY,
                        "A run contract must pin each Case exactly once");
            }
        }
    }

    /** The canonical hash of the whole frozen contract; identical inputs produce an identical hash. */
    public ContentHash contentHash() {
        return CANONICAL_JSON.hash(this);
    }

    /** Every Case id in frozen manifest order. */
    public List<String> caseIds() {
        List<String> ids = new ArrayList<>(cases.size());
        for (EvaluationCasePins pins : cases) {
            ids.add(pins.caseId());
        }
        return List.copyOf(ids);
    }

    /** The total number of attempts this contract requires, high-risk Cases included. */
    public int requiredAttemptCount() {
        int total = 0;
        for (EvaluationCasePins pins : cases) {
            total += pins.repeats();
        }
        return total;
    }

    /** The exact provider, model and immutable model version of one run. */
    public record ModelPin(String provider, String model, String modelVersion) {

        public ModelPin {
            if (provider == null || model == null || modelVersion == null
                    || !provider.matches(VERSION_REFERENCE)
                    || !model.matches(VERSION_REFERENCE)
                    || !modelVersion.matches(VERSION_REFERENCE)) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING,
                        "A model pin requires an exact bounded provider, model and immutable version");
            }
        }
    }

    /**
     * The system-prompt, Skill and Tool-schema content hashes.
     *
     * <p>An artifact that genuinely does not exist is recorded as honestly absent with a bounded reason rather
     * than as a placeholder hash, so a reader cannot mistake a fabricated hash for a verified one.
     */
    public record ArtifactPins(List<ArtifactPin> artifacts) {

        /** The artifact identifiers every run must account for. */
        public static final List<String> REQUIRED_ARTIFACTS =
                List.of("system-prompt", "skill", "tool-schema");

        public ArtifactPins {
            if (artifacts == null) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING, "Artifact pins must be declared, even when absent");
            }
            artifacts = List.copyOf(new ArrayList<>(artifacts));
            Set<String> ids = new LinkedHashSet<>();
            for (ArtifactPin pin : artifacts) {
                if (!ids.add(pin.artifactId())) {
                    throw EvaluationRunException.of(
                            EvaluationRunErrorCodes.PIN_CONTRADICTORY,
                            "Artifact pins must name each required artifact exactly once");
                }
            }
            if (!ids.containsAll(REQUIRED_ARTIFACTS)) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING,
                        "Artifact pins must account for the system prompt, the Skill and the Tool schema");
            }
        }

        /** The pin of one artifact id, or {@code null} when the run does not account for it. */
        public ArtifactPin artifact(String artifactId) {
            for (ArtifactPin pin : artifacts) {
                if (pin.artifactId().equals(artifactId)) {
                    return pin;
                }
            }
            return null;
        }
    }

    /** One artifact pin: either a verified content hash, or an honest, reasoned absence. */
    public record ArtifactPin(String artifactId, ContentHash contentHash, String absenceReason) {

        public ArtifactPin {
            if (artifactId == null || !artifactId.matches(ContractPatterns.REFERENCE_ID)) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING, "An artifact pin requires a bounded artifact id");
            }
            if ((contentHash == null) == (absenceReason == null)) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_CONTRADICTORY,
                        "An artifact pin must be either present with a hash or honestly absent with a reason");
            }
        }

        /** A verified present artifact. */
        public static ArtifactPin present(String artifactId, ContentHash contentHash) {
            return new ArtifactPin(artifactId, contentHash, null);
        }

        /** An honestly absent artifact. */
        public static ArtifactPin absent(String artifactId, String absenceReason) {
            return new ArtifactPin(artifactId, null, absenceReason);
        }

        public boolean isPresent() {
            return contentHash != null;
        }
    }

    /** The Intent parser/codec, resolver, canonicalizer, validator and planner versions. */
    public record ParserPins(
            String intentParserVersion,
            String intentCodecVersion,
            String resolverVersion,
            String canonicalizerVersion,
            String validatorVersion,
            String plannerVersion
    ) {

        public ParserPins {
            for (String version : List.of(
                    intentParserVersion, intentCodecVersion, resolverVersion, canonicalizerVersion,
                    validatorVersion, plannerVersion)) {
                if (version == null || !version.matches(VERSION_REFERENCE)) {
                    throw EvaluationRunException.of(
                            EvaluationRunErrorCodes.PIN_MISSING, "A parser pin requires every version reference");
                }
            }
        }
    }

    /** The Metadata catalog pins; the per-Case snapshot pins travel with each Case. */
    public record MetadataPins(String catalogVersion, ContentHash catalogHash) {

        public MetadataPins {
            if (catalogVersion == null || catalogHash == null) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING, "Metadata pins require a catalog version and hash");
            }
        }
    }

    /** The candidate-corpus pins, recorded separately from the review-readiness pins. */
    public record CandidateCorpusPins(
            String corpusVersion,
            ContentHash corpusHash,
            ContentHash manifestHash,
            String metadataCatalogVersion,
            ContentHash metadataCatalogHash,
            String fixtureCatalogVersion,
            ContentHash fixtureCatalogHash,
            int caseCount,
            int minimumCasesPerCategory,
            int requiredHighRiskRepeats
    ) {

        public CandidateCorpusPins {
            if (corpusVersion == null || metadataCatalogVersion == null || fixtureCatalogVersion == null) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING, "Candidate-corpus pins require every version");
            }
            if (corpusHash == null || manifestHash == null || metadataCatalogHash == null
                    || fixtureCatalogHash == null) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING, "Candidate-corpus pins require every content hash");
            }
            if (caseCount < 1 || minimumCasesPerCategory < 1 || requiredHighRiskRepeats < 1) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING, "Candidate-corpus pins require their counts");
            }
        }
    }

    /**
     * The review-readiness pins obtained at run start.
     *
     * <p>They are frozen with the report: a report that recorded {@code DEVELOPMENT_ONLY} can never be
     * relabelled after the human review state changes, because the classification, the issue codes and the
     * review-set hash are part of the frozen contract and of the report hash.
     */
    public record ReviewPins(
            CorpusUsageClassification classification,
            List<String> issueCodes,
            ContentHash reviewSetHash,
            ContentHash manifestHash
    ) {

        public ReviewPins {
            if (classification == null || reviewSetHash == null || manifestHash == null) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING, "Review pins require the frozen classification");
            }
            if (issueCodes == null) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING, "Review pins require the issue-code list");
            }
            issueCodes = List.copyOf(new ArrayList<>(issueCodes));
            for (String code : issueCodes) {
                if (code == null || !code.matches(ContractPatterns.MACHINE_CODE)) {
                    throw EvaluationRunException.of(
                            EvaluationRunErrorCodes.PIN_MISSING, "A review issue code must be a bounded code");
                }
            }
            if (classification.isGateEligible() == !issueCodes.isEmpty()) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_CONTRADICTORY,
                        "A gate-eligible classification carries no issue codes, and a lesser one carries them");
            }
        }
    }

    /** The frozen time context of the run. */
    public record TimePins(Instant asOf, String timezone, Instant dataWatermark) {

        public TimePins {
            if (asOf == null || timezone == null || dataWatermark == null) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING, "Time pins require asOf, timezone and watermark");
            }
            if (dataWatermark.isAfter(asOf)) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_CONTRADICTORY, "A data watermark must not be after asOf");
            }
            try {
                java.time.ZoneId.of(timezone);
            } catch (RuntimeException failure) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING, "A time pin requires a valid timezone id");
            }
        }
    }

    /** The explicit code revision and bounded environment identifier. */
    public record CodePins(String codeRevision, String environment) {

        public CodePins {
            if (codeRevision == null || !codeRevision.matches(VERSION_REFERENCE)) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING, "A code revision must be explicit and bounded");
            }
            if (environment == null || !environment.matches(ContractPatterns.REFERENCE_ID)) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.PIN_MISSING, "An environment must be explicit and bounded");
            }
        }
    }
}