package com.company.campaign.evaluation.segment.contract;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.shared.ImmutableValues;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One Golden Corpus case input: what the product owner asked for, and nothing about what the answer is.
 *
 * <p>The case deliberately cannot carry expected answers. {@code @JsonIgnoreProperties(ignoreUnknown =
 * false)} plus strict binding means a file that contains {@code expectedDisposition},
 * {@code expectedCanonicalAst} or {@code expectedMemberIdsByFixture} fails to load at all, so a case and
 * its expected result can never be authored as one document and then drift apart.
 *
 * <p>Trusted execution context is referenced, never inlined. A case names an
 * {@link FixtureRole#EXECUTION_CONTEXT} fixture, which keeps tenant, user, DataScope, locale, timezone and
 * watermark out of the case file entirely: there is no field here for a forged value to land in.
 *
 * <p>{@code complexSemantics} is a boxed flag so that omitting it is a binding failure rather than a silent
 * {@code false}. A case that lost its complexity marker would otherwise quietly stop demanding a
 * counterexample fixture, which is exactly the kind of weakening the corpus must not absorb.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaseInput(
        SchemaVersion schemaVersion,
        String caseId,
        SegmentCategory category,
        RiskLevel riskLevel,
        List<LocaleTag> locales,
        String utterance,
        CurrentSegmentRef currentSegment,
        String executionContextFixture,
        String metadataSnapshot,
        List<String> fixtures,
        Boolean complexSemantics,
        List<String> tags,
        String notes
) {
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Comparator<String> CANONICAL_ORDER = Comparator.naturalOrder();
    private static final int MAX_TAG_LENGTH = 64;

    public CaseInput {
        if (schemaVersion == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID, "Case must declare a schema version");
        }
        if (caseId == null || !REFERENCE_ID.matcher(caseId).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Case id '" + caseId + "' is not a valid reference identifier");
        }
        if (category == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID, "Case '" + caseId + "' must declare its category");
        }
        if (riskLevel == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID, "Case '" + caseId + "' must declare its risk level");
        }
        if (locales == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID, "Case '" + caseId + "' must declare its locale list");
        }
        if (utterance == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID, "Case '" + caseId + "' must carry its utterance");
        }
        if (executionContextFixture == null || !REFERENCE_ID.matcher(executionContextFixture).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Case '" + caseId + "' execution context fixture '" + executionContextFixture
                            + "' is not a valid reference identifier");
        }
        if (metadataSnapshot == null || !REFERENCE_ID.matcher(metadataSnapshot).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Case '" + caseId + "' metadata snapshot '" + metadataSnapshot
                            + "' is not a valid reference identifier");
        }
        if (fixtures == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID, "Case '" + caseId + "' must declare its fixture list");
        }
        if (complexSemantics == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ASSET_INVALID,
                    "Case '" + caseId + "' must declare complexSemantics explicitly");
        }

        locales = normalizeLocales(caseId, locales);
        fixtures = normalizeReferences(caseId, "fixtures", fixtures);
        tags = normalizeTags(caseId, tags);
    }

    private static List<LocaleTag> normalizeLocales(String caseId, List<LocaleTag> locales) {
        List<LocaleTag> checked = new ArrayList<>(locales.size());
        Set<String> seen = new HashSet<>(locales.size());
        for (LocaleTag locale : locales) {
            if (locale == null) {
                throw new EvaluationException(
                        EvaluationErrorCodes.ASSET_INVALID,
                        "Case '" + caseId + "' contains a null locale tag");
            }
            if (!seen.add(locale.value())) {
                throw new EvaluationException(
                        EvaluationErrorCodes.DUPLICATE_SET_VALUE,
                        "Case '" + caseId + "' declares locale '" + locale.value() + "' more than once",
                        Map.of("caseId", caseId, "locale", locale.value()));
            }
            checked.add(locale);
        }
        // Locale order carries no meaning; sorting keeps canonical bytes independent of authoring order.
        checked.sort(Comparator.comparing(LocaleTag::value, CANONICAL_ORDER));
        return List.copyOf(checked);
    }

    private static List<String> normalizeReferences(String caseId, String fieldName, List<String> references) {
        List<String> checked = new ArrayList<>(references.size());
        Set<String> seen = new HashSet<>(references.size());
        for (String reference : references) {
            if (reference == null || !REFERENCE_ID.matcher(reference).matches()) {
                throw new EvaluationException(
                        EvaluationErrorCodes.ID_INVALID,
                        "Case '" + caseId + "' " + fieldName + " entry '" + reference
                                + "' is not a valid reference identifier");
            }
            if (!seen.add(reference)) {
                throw new EvaluationException(
                        EvaluationErrorCodes.DUPLICATE_ID,
                        "Case '" + caseId + "' references '" + reference + "' more than once in " + fieldName,
                        Map.of("caseId", caseId, "reference", reference));
            }
            checked.add(reference);
        }
        return List.copyOf(checked);
    }

    private static List<String> normalizeTags(String caseId, List<String> tags) {
        List<String> checked = new ArrayList<>(tags == null ? 0 : tags.size());
        Set<String> seen = new HashSet<>();
        for (String tag : tags == null ? List.<String>of() : tags) {
            if (tag == null || tag.isBlank() || tag.length() > MAX_TAG_LENGTH) {
                throw new EvaluationException(
                        EvaluationErrorCodes.ASSET_INVALID,
                        "Case '" + caseId + "' has an empty or oversized review tag");
            }
            if (!seen.add(tag)) {
                throw new EvaluationException(
                        EvaluationErrorCodes.DUPLICATE_SET_VALUE,
                        "Case '" + caseId + "' declares review tag '" + tag + "' more than once");
            }
            checked.add(tag);
        }
        return ImmutableValues.freezeList(checked);
    }

    /** True when the corpus must supply at least two fixtures including a counterexample. */
    public boolean isComplexSemantics() {
        return Boolean.TRUE.equals(complexSemantics);
    }
}