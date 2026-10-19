package com.company.campaign.evaluation.corpus;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.evaluation.segment.contract.RefinementOperation;
import com.company.campaign.evaluation.segment.contract.RefinementTargetPlaceholder;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static com.company.campaign.evaluation.corpus.CorpusV1Support.CONTEXT_PRIMARY;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.PRIMARY_MEMBERS;

/**
 * The declarative blueprint of one candidate case: what the user asked, and what the corpus freezes as
 * the expected answer, in a form the generator can turn into the exact contract objects.
 *
 * <p>A blueprint is not an asset. It is the reviewed intent; the generator binds it to the Metadata
 * snapshot, computes every hash through T04/T05, and writes the canonical bytes. Keeping the intent data
 * separate is what lets the corpus be reviewed as semantics rather than as JSON.
 */
final class CorpusV1Blueprints {

    /** One grounded Intent slot. {@code slotText} is located in the utterance to compute the span. */
    record Slot(String id, String concept, String operator, boolean grounded, String slotText, JsonNode value) {
    }

    /** One case blueprint. */
    record CaseSpec(
            String slug,
            String subcase,
            String utterance,
            List<LocaleTag> locales,
            RiskLevel risk,
            boolean complex,
            Disposition disposition,
            String executionContextFixture,
            String primaryFixture,
            String counterFixture,
            List<Slot> slots,
            List<String> selectedCandidates,
            List<String> rejectedCandidates,
            Predicate predicate,
            List<String> validationCodes,
            List<String> questionCodes,
            List<String> forbiddenAssumptions,
            List<String> claimIds,
            RefinementTargetPlaceholder refinement,
            List<String> primaryMembers,
            List<String> counterMembers) {

        List<String> fixtures() {
            List<String> fixtures = new ArrayList<>();
            fixtures.add(primaryFixture);
            if (counterFixture != null) {
                fixtures.add(counterFixture);
            }
            return List.copyOf(fixtures);
        }

        List<String> semanticTags() {
            List<String> tags = new ArrayList<>();
            tags.add(subcase);
            return List.copyOf(tags);
        }
    }

    /** Fluent builder with reviewable defaults, so each case states only what makes it distinct. */
    static final class B {

        private final String slug;
        private final String utterance;
        private String subcase;
        private List<LocaleTag> locales = CorpusV1Support.zhOnly();
        private RiskLevel risk = RiskLevel.MEDIUM;
        private boolean complex;
        private Disposition disposition = Disposition.RESOLVED;
        private String executionContextFixture = CONTEXT_PRIMARY;
        private String primaryFixture = PRIMARY_MEMBERS;
        private String counterFixture;
        private List<Slot> slots = new ArrayList<>();
        private List<String> selected = new ArrayList<>();
        private List<String> rejected = new ArrayList<>();
        private Predicate predicate;
        private List<String> validation = new ArrayList<>();
        private List<String> questions = new ArrayList<>();
        private List<String> forbidden = new ArrayList<>();
        private List<String> claims = new ArrayList<>();
        private RefinementTargetPlaceholder refinement;
        private List<String> primaryMembers = List.of("m-001", "m-002");
        private List<String> counterMembers = List.of("m-003");

        private B(String slug, String utterance) {
            this.slug = slug;
            this.utterance = utterance;
            this.subcase = slug;
        }

        B subcase(String value) {
            this.subcase = value;
            return this;
        }

        B locales(List<LocaleTag> value) {
            this.locales = value;
            return this;
        }

        B risk(RiskLevel value) {
            this.risk = value;
            return this;
        }

        B complex(boolean value) {
            this.complex = value;
            return this;
        }

        B disposition(Disposition value) {
            this.disposition = value;
            return this;
        }

        B context(String value) {
            this.executionContextFixture = value;
            return this;
        }

        B fixtures(String primary, String counter) {
            this.primaryFixture = primary;
            this.counterFixture = counter;
            this.complex = counter != null;
            return this;
        }

        B slot(String id, String concept, String operator, String slotText, JsonNode value) {
            slots.add(new Slot(id, concept, operator, true, slotText, value));
            return this;
        }

        B ungrounded(String id, String concept, String operator, JsonNode value) {
            slots.add(new Slot(id, concept, operator, false, null, value));
            return this;
        }

        B sel(String... ids) {
            selected = new ArrayList<>(List.of(ids));
            return this;
        }

        B rej(String... ids) {
            rejected = new ArrayList<>(List.of(ids));
            return this;
        }

        B ast(Predicate value) {
            this.predicate = value;
            return this;
        }

        B validate(String... codes) {
            validation = new ArrayList<>(List.of(codes));
            return this;
        }

        B ask(String... codes) {
            questions = new ArrayList<>(List.of(codes));
            return this;
        }

        B forbid(String... codes) {
            forbidden = new ArrayList<>(List.of(codes));
            return this;
        }

        B claims(String... codes) {
            claims = new ArrayList<>(List.of(codes));
            return this;
        }

        B refine(RefinementOperation operation, String target, String scope) {
            this.refinement = new RefinementTargetPlaceholder(operation, target, scope);
            return this;
        }

        B members(List<String> primary, List<String> counter) {
            this.primaryMembers = primary;
            this.counterMembers = counter;
            return this;
        }

        CaseSpec build() {
            return new CaseSpec(slug, subcase, utterance, locales, risk, complex, disposition,
                    executionContextFixture, primaryFixture, counterFixture, List.copyOf(slots),
                    List.copyOf(selected), List.copyOf(rejected), predicate, List.copyOf(validation),
                    List.copyOf(questions), List.copyOf(forbidden), List.copyOf(claims), refinement,
                    primaryMembers, counterMembers);
        }
    }

    static B c(String slug, String utterance) {
        return new B(slug, utterance);
    }

    static JsonNode text(String value) {
        return CorpusV1Support.jsonValue(value);
    }

    static JsonNode number(long value) {
        return CorpusV1Support.jsonNumber(value);
    }

    private CorpusV1Blueprints() {
    }

    /** Every candidate case, in specification-category order and then in declared sub-case order. */
    static List<CaseSpec> all() {
        List<CaseSpec> cases = new ArrayList<>();
        cases.addAll(Category01AttributeEquality.cases());
        cases.addAll(Category02BooleanGroups.cases());
        cases.addAll(Category03NotNullNegative.cases());
        cases.addAll(Category04Boundaries.cases());
        cases.addAll(Category05TimeWindows.cases());
        cases.addAll(Category06Aggregates.cases());
        cases.addAll(Category07Currency.cases());
        cases.addAll(Category08ExistsSameRow.cases());
        cases.addAll(Category09ExistsDifferentRows.cases());
        cases.addAll(Category10SingleEventVersusAggregate.cases());
        cases.addAll(Category11UnsupportedAndRawSql.cases());
        cases.addAll(Category12CandidatesAndClarification.cases());
        cases.addAll(Category13Refinements.cases());
        cases.addAll(Category14MultilingualAliases.cases());
        cases.addAll(Category15Security.cases());
        return List.copyOf(cases);
    }
}