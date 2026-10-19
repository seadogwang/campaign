package com.company.campaign.query.fixture;

import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.testsupport.ReferenceQueryFixtures;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.segment.reference.FixtureAudienceMembershipDataset;
import com.company.campaign.segment.reference.InMemoryAudienceVersionCatalog;
import com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.EXECUTOR;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.UNIVERSE;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.load;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardMapping;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardPayload;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T18: fixture preview execution resolves an Existing Audience Reference by its exact immutable pin.
 *
 * <p>Membership is looked up under the full pin — audience, version identity and content hash — so a set
 * registered for different bytes can never answer. An unknown pin refuses rather than evaluating as "no
 * members", the plan's effective DataScope still applies before the reference, ordinary {@code AND}/{@code OR}/
 * {@code NOT} composition keeps its semantics, and no member identifier leaves the executor.
 */
class FixtureExecutorAudienceReferenceTest {

    private static final AudienceVersionRef VERSION = ReferenceQueryFixtures.version();

    @TempDir
    Path repositoryRoot;

    private static MemberIdSet evaluate(
            FixtureDataset dataset,
            SemanticQueryPlan plan,
            MemberIdSet effectiveMembers,
            com.company.campaign.segment.reference.AudienceMembershipPort membership) {
        return EXECUTOR.execute(
                plan,
                dataset,
                standardMapping(),
                ReferenceQueryFixtures.referenceScope(UNIVERSE, effectiveMembers),
                null,
                membership).memberIdSet();
    }

    @Test
    void onlyMembersOfTheExactVersionAreMatched() {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        SemanticQueryPlan plan = ReferenceQueryFixtures.plan(ReferenceQueryFixtures.referenceDocument(VERSION), catalog);

        MemberIdSet matched = evaluate(dataset, plan, UNIVERSE, ReferenceQueryFixtures.members(
                VERSION, Set.of("m-001", "m-003", "m-005")));

        assertThat(matched).isEqualTo(MemberIdSet.of("m-001", "m-003", "m-005"));
    }

    @Test
    void theEffectiveDataScopeIsStillAppliedBeforeTheReference() {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        SemanticQueryPlan plan = ReferenceQueryFixtures.plan(ReferenceQueryFixtures.referenceDocument(VERSION), catalog);

        MemberIdSet matched = evaluate(dataset, plan, MemberIdSet.of("m-001", "m-002"),
                ReferenceQueryFixtures.members(VERSION, Set.of("m-001", "m-003", "m-005")));

        // m-003 and m-005 are in the version but outside the effective scope the plan requires.
        assertThat(matched).isEqualTo(MemberIdSet.of("m-001"));
    }

    @Test
    void aMembershipSetRegisteredForDifferentBytesNeverAnswers() {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        SemanticQueryPlan plan = ReferenceQueryFixtures.plan(ReferenceQueryFixtures.referenceDocument(VERSION), catalog);
        // The same audience and version identity, but a pin whose content hash names different bytes.
        FixtureAudienceMembershipDataset otherBytes = ReferenceQueryFixtures.members(
                ReferenceQueryFixtures.version("audience-vip", "version-8"), Set.of("m-001"));

        ContractAssertions.assertFailsWithCode(
                () -> evaluate(dataset, plan, UNIVERSE, otherBytes),
                FixtureErrorCodes.FIXTURE_REFERENCE_MEMBERSHIP_MISSING);
    }

    @Test
    void anUnregisteredPinRefusesRatherThanMatchingNothing() {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        SemanticQueryPlan plan = ReferenceQueryFixtures.plan(ReferenceQueryFixtures.referenceDocument(VERSION), catalog);

        ContractAssertions.assertFailsWithCode(
                () -> evaluate(dataset, plan, UNIVERSE, ReferenceQueryFixtures.noMembers()),
                FixtureErrorCodes.FIXTURE_REFERENCE_MEMBERSHIP_MISSING);
    }

    @Test
    void aReferenceComposesWithNotAndOrUnderItsNormalSemantics() {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        FixtureAudienceMembershipDataset membership =
                ReferenceQueryFixtures.members(VERSION, Set.of("m-001", "m-002"));

        Predicate negated = SegmentValidationFixtures.not("n-not", ReferenceQueryFixtures.reference("n-ref", VERSION));
        Predicate disjoined = SegmentValidationFixtures.any("n-any", List.of(
                ReferenceQueryFixtures.reference("n-ref", VERSION),
                SegmentValidationFixtures.tierEquals("n-tier", "GOLD")));

        MemberIdSet negatedMatches = evaluate(
                dataset,
                ReferenceQueryFixtures.plan(negated, catalog),
                MemberIdSet.of("m-001", "m-002", "m-003"),
                membership);
        MemberIdSet disjoinedMatches = evaluate(
                dataset,
                ReferenceQueryFixtures.plan(disjoined, catalog),
                UNIVERSE,
                membership);

        assertThat(negatedMatches).isEqualTo(MemberIdSet.of("m-003"));
        assertThat(disjoinedMatches.memberIds()).contains("m-001", "m-002");
    }

    @Test
    void theAcceptedFiveArgumentEntryPointRefusesAReferencePlan() {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        SemanticQueryPlan plan = ReferenceQueryFixtures.plan(ReferenceQueryFixtures.referenceDocument(VERSION), catalog);

        ContractAssertions.assertFailsWithCode(
                () -> EXECUTOR.execute(
                        plan, dataset, standardMapping(),
                        ReferenceQueryFixtures.referenceScope(UNIVERSE, UNIVERSE), null),
                FixtureErrorCodes.FIXTURE_UNSUPPORTED_SEMANTIC);
    }

    @Test
    void noMemberIdentifierLeavesTheExecutorOrAMembershipSet() {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        SemanticQueryPlan plan = ReferenceQueryFixtures.plan(ReferenceQueryFixtures.referenceDocument(VERSION), catalog);

        FixtureExecutionResult result = EXECUTOR.execute(
                plan,
                dataset,
                standardMapping(),
                ReferenceQueryFixtures.referenceScope(UNIVERSE, UNIVERSE),
                null,
                ReferenceQueryFixtures.members(VERSION, Set.of("m-001")));

        assertThat(result.toString())
                .doesNotContain("m-001")
                .doesNotContain(dataset.fixtureId())
                .doesNotContain(VERSION.audienceId());
        assertThat(result.conditionStatistics()).isNotEmpty();
        assertThat(ReferenceQueryFixtures.members(VERSION, Set.of("m-001")).lookup(
                VERSION, SegmentReferenceFixtures.scope(SegmentReferenceFixtures.AUDIENCE_SCOPE)).orElseThrow()
                .toString())
                .doesNotContain("m-001")
                .contains(VERSION.identityKey());
    }

    @Test
    void executionIsDeterministicForAReferenceBearingPlan() {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());
        InMemoryAudienceVersionCatalog catalog = ReferenceQueryFixtures.catalogOf(VERSION);
        SemanticQueryPlan plan = ReferenceQueryFixtures.plan(ReferenceQueryFixtures.referenceDocument(VERSION), catalog);
        FixtureAudienceMembershipDataset membership =
                ReferenceQueryFixtures.members(VERSION, Set.of("m-002", "m-004"));

        FixtureExecutionResult first = EXECUTOR.execute(
                plan, dataset, standardMapping(),
                ReferenceQueryFixtures.referenceScope(UNIVERSE, UNIVERSE), null, membership);
        FixtureExecutionResult second = EXECUTOR.execute(
                plan, dataset, standardMapping(),
                ReferenceQueryFixtures.referenceScope(UNIVERSE, UNIVERSE), null, membership);

        assertThat(second).isEqualTo(first);
        assertThat(second.resultHash()).isEqualTo(first.resultHash());
        assertThat(first.memberIdSet()).isEqualTo(MemberIdSet.of("m-002", "m-004"));
    }
}