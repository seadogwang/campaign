package com.company.campaign.evaluation.segment.reference;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.company.campaign.evaluation.segment.contract.EvaluationVersions;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.shared.ImmutableValues;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One reference-corpus case: the reviewed statement of what a reference scenario asks and what it must do.
 *
 * <p>A case is a <em>question</em>, not an answer. It declares the category it covers, the scenario it
 * drives, the trusted fixtures it uses and the exact request it makes; the paired expected-result file
 * declares what the reference core must observe. Keeping the two apart is what lets a corpus change be
 * reviewed as a change of question and of answer separately.
 *
 * <p>Nothing here is a trusted fact. The case never declares an owner, a permission, an effective DataScope,
 * a dependency or a readiness state: it names fixtures, and every trusted value is read from the host-owned
 * fixture ports at evaluation time.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReferenceCorpusCase(
        SchemaVersion schemaVersion,
        String caseId,
        ReferenceCategory category,
        ReferenceScenario scenario,
        RiskLevel riskLevel,
        List<String> tags,
        String notes,
        ReferenceCaseFixtures fixtures,
        ReferenceCaseRequest request
) {

    /** The substrings that betray a stub rather than a reviewed case statement. */
    private static final List<String> PLACEHOLDER_TOKENS = List.of(
            "todo", "tbd", "fixme", "placeholder", "lorem ipsum", "dummy", "change me", "xxx");

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public ReferenceCorpusCase {
        EvaluationVersions.requireSupported(schemaVersion);
        if (caseId == null || !REFERENCE_ID.matcher(caseId).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Reference corpus case id '" + caseId + "' is not a valid reference identifier");
        }
        if (category == null || scenario == null) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED,
                    "Reference corpus case '" + caseId + "' must declare its category and its scenario");
        }
        if (riskLevel == null) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED,
                    "Reference corpus case '" + caseId + "' must declare its risk level");
        }
        if (notes == null || notes.isBlank()) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED,
                    "Reference corpus case '" + caseId + "' must explain what it proves");
        }
        if (fixtures == null || request == null) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.CASE_UNDER_SPECIFIED,
                    "Reference corpus case '" + caseId + "' must declare its fixtures and its request");
        }
        tags = ImmutableValues.freezeList(tags == null ? List.of() : tags);
        requireNoPlaceholder(caseId, notes, tags);
    }

    /** The fixture identity declared for one role, or {@code null} when the case declares none. */
    public String fixtureFor(ReferenceFixtureRole role) {
        return fixtures.forRole(role);
    }

    /** The stable identity keys of the declared reference sites, in declaration order. */
    public List<String> siteKeys() {
        List<String> keys = new ArrayList<>();
        for (ReferenceSiteSpec site : request.declaredSites()) {
            keys.add(site.siteKey());
        }
        return List.copyOf(keys);
    }

    /** The distinct exact pin keys the declared sites name, in declaration order. */
    public List<String> distinctPinKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (ReferenceSiteSpec site : request.declaredSites()) {
            keys.add(site.pinKey());
        }
        return List.copyOf(keys);
    }

    private static void requireNoPlaceholder(String caseId, String notes, List<String> tags) {
        List<String> text = new ArrayList<>(tags);
        text.add(notes);
        for (String value : text) {
            String lowered = value == null ? "" : value.toLowerCase(Locale.ROOT);
            for (String token : PLACEHOLDER_TOKENS) {
                if (lowered.contains(token)) {
                    throw new ReferenceCorpusException(
                            ReferenceCorpusErrorCodes.PLACEHOLDER_TEXT,
                            "Reference corpus case '" + caseId + "' carries placeholder text rather than a"
                                    + " reviewable statement",
                            Map.of("caseId", caseId));
                }
            }
        }
    }
}