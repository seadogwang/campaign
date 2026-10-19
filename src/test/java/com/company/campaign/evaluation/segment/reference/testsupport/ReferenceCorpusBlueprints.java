package com.company.campaign.evaluation.segment.reference.testsupport;

import com.company.campaign.evaluation.segment.contract.CaseReviewEntry;
import com.company.campaign.evaluation.segment.contract.ReviewRole;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.reference.ReferenceCaseFixtures;
import com.company.campaign.evaluation.segment.reference.ReferenceCaseRequest;
import com.company.campaign.evaluation.segment.reference.ReferenceCategory;
import com.company.campaign.evaluation.segment.reference.ReferenceCorpusCase;
import com.company.campaign.evaluation.segment.reference.ReferenceCorpusExpected;
import com.company.campaign.evaluation.segment.reference.ReferenceExpectedGraph;
import com.company.campaign.evaluation.segment.reference.ReferenceExpectedMembership;
import com.company.campaign.evaluation.segment.reference.ReferenceExpectedRecheck;
import com.company.campaign.evaluation.segment.reference.ReferenceExpectedResolution;
import com.company.campaign.evaluation.segment.reference.ReferenceExpectedRetention;
import com.company.campaign.evaluation.segment.reference.ReferenceFixtureRole;
import com.company.campaign.evaluation.segment.reference.ReferenceOutcome;
import com.company.campaign.evaluation.segment.reference.ReferencePin;
import com.company.campaign.evaluation.segment.reference.ReferenceRecheck;
import com.company.campaign.evaluation.segment.reference.ReferenceRecheckKind;
import com.company.campaign.evaluation.segment.reference.ReferenceRecheckOutcome;
import com.company.campaign.evaluation.segment.reference.ReferenceRetentionRequest;
import com.company.campaign.evaluation.segment.reference.ReferenceScenario;
import com.company.campaign.evaluation.segment.reference.ReferenceSiteSpec;
import com.company.campaign.evaluation.segment.reference.ReferenceCorpusVersions;
import com.company.campaign.evaluation.segment.reference.SubjectKind;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reviewed blueprints of the T18 Existing Audience Reference corpus.
 *
 * <p>Every blueprint is a pure literal: no clock, no random source, no default locale and no filesystem.
 * The same file always produces the same assets, so a corpus change is a reviewable diff of this file.
 *
 * <p>Two things are deliberately absent from every asset. No asset carries a raw member identifier: the
 * membership fixture records only a member-set hash and a count, and the expectation records only the hash.
 * And no asset carries an owner, a permission, an effective DataScope or a readiness claim: those are read
 * from the trusted fixtures at evaluation time, never declared here.
 */
final class ReferenceCorpusBlueprints {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private static final String ROOT = ReferenceCorpusVersions.CORPUS_ROOT;

    // --- fixture identities ------------------------------------------------------------------------

    static final String CATALOG_MAIN = "fixture-ref-catalog-main-001";
    static final String CATALOG_TENANT_002 = "fixture-ref-catalog-tenant-002";
    static final String DATASCOPE_PRIMARY = "fixture-ref-datascope-primary-001";
    static final String DATASCOPE_NARROW = "fixture-ref-datascope-narrow-002";
    static final String DATASCOPE_EMPTY = "fixture-ref-datascope-empty-003";
    static final String PROGRAM_ALLOWED = "fixture-ref-program-policy-allowed-001";
    static final String PROGRAM_DENIED = "fixture-ref-program-policy-denied-002";
    static final String POLICY_BOUNDED = "fixture-ref-policy-bounded-001";
    static final String POLICY_STRICT = "fixture-ref-policy-strict-002";
    static final String DIRECTORY_MAIN = "fixture-ref-directory-main-001";
    static final String DIRECTORY_AMBIGUOUS = "fixture-ref-directory-ambiguous-002";
    static final String DIRECTORY_DENIED = "fixture-ref-directory-denied-003";
    static final String DIRECTORY_EMPTY = "fixture-ref-directory-empty-004";
    static final String MEMBERSHIP_MAIN = "fixture-ref-membership-main-001";
    static final String RETENTION_HOLD = "fixture-ref-retention-policy-hold-001";
    static final String RETENTION_RELEASE = "fixture-ref-retention-policy-release-002";
    static final String CONTEXT_PRIMARY = "fixture-ref-context-primary-001";
    static final String CONTEXT_NARROW = "fixture-ref-context-narrow-002";
    static final String METADATA_MAIN = "fixture-ref-metadata-001";

    // --- stable codes the reference core owns ------------------------------------------------------

    static final String VERSION_NOT_FOUND = "SEGMENT_REFERENCE_VERSION_NOT_FOUND";
    static final String VERSION_CONFLICT = "SEGMENT_REFERENCE_VERSION_CONFLICT";
    static final String VERSION_UNAVAILABLE = "SEGMENT_REFERENCE_VERSION_UNAVAILABLE";
    static final String PROGRAM_NOT_ALLOWED = "SEGMENT_REFERENCE_PROGRAM_NOT_ALLOWED";
    static final String RESOURCE_NOT_ACCESSIBLE = "SEGMENT_REFERENCE_RESOURCE_NOT_ACCESSIBLE";
    static final String DATASCOPE_INTERSECTION_EMPTY = "SEGMENT_REFERENCE_DATASCOPE_INTERSECTION_EMPTY";
    static final String DATASCOPE_UNAVAILABLE = "SEGMENT_REFERENCE_DATASCOPE_UNAVAILABLE";
    static final String SUBJECT_MISMATCH = "SEGMENT_REFERENCE_SUBJECT_MISMATCH";
    static final String CYCLE_DETECTED = "SEGMENT_REFERENCE_CYCLE_DETECTED";
    static final String DEPTH_EXCEEDED = "SEGMENT_REFERENCE_DEPTH_EXCEEDED";
    static final String DISTINCT_VERSIONS_EXCEEDED = "SEGMENT_REFERENCE_DISTINCT_VERSIONS_EXCEEDED";
    static final String COMPLEXITY_EXCEEDED = "SEGMENT_REFERENCE_EXPANDED_COMPLEXITY_EXCEEDED";
    static final String CONTEXT_MISSING = "SEGMENT_REFERENCE_CONTEXT_MISSING";

    static final String SELECT_AUDIENCE = "SELECT_AUDIENCE_REFERENCE";
    static final String SELECT_VERSION = "SELECT_AUDIENCE_VERSION";
    static final String REFERENCE_UNAVAILABLE = "AUDIENCE_REFERENCE_UNAVAILABLE";

    // --- the pinned reference universe -------------------------------------------------------------

    static final ReferencePin GOLD_V1 = pin("audience-loyalty-gold", "av-gold-001");
    static final ReferencePin GOLD_V2 = pin("audience-loyalty-gold", "av-gold-002");
    static final ReferencePin SILVER_V1 = pin("audience-loyalty-silver", "av-silver-001");
    static final ReferencePin BRONZE_V1 = pin("audience-loyalty-bronze", "av-bronze-001");
    static final ReferencePin EVENTS_V1 = pin("audience-events-active", "av-events-001");
    static final ReferencePin TRAVEL_V1 = pin("audience-travel-frequent", "av-travel-001");
    static final ReferencePin PLATINUM_V1 = pin("audience-loyalty-platinum", "av-platinum-001");

    private ReferenceCorpusBlueprints() {
    }

    /** One fixture payload: identity, role, revision and the payload value that is hashed. */
    record FixtureSpec(String fixtureId, ReferenceFixtureRole role, String fixtureVersion, Object payload) {
    }

    /** One case: the question it asks and the expectation paired with it. */
    record CaseBlueprint(ReferenceCorpusCase caseInput, ReferenceCorpusExpected expected) {
    }

    // ------------------------------------------------------------------ fixtures

    static List<FixtureSpec> fixtures() {
        List<FixtureSpec> specs = new ArrayList<>();
        specs.add(new FixtureSpec(METADATA_MAIN, ReferenceFixtureRole.METADATA_SNAPSHOT, "1",
                map("snapshotId", "metadata-ref-001", "subject", "MEMBER",
                        "attributeCount", 6, "metricCount", 3, "relationCount", 2)));

        specs.add(new FixtureSpec(CATALOG_MAIN, ReferenceFixtureRole.VERSION_CATALOG, "1",
                catalog("catalog-main-001", "tenant-001", List.of(
                        version(GOLD_V1, "program-001", "MEMBER", 1, List.of(SILVER_V1)),
                        version(GOLD_V2, "program-001", "MEMBER", 1, List.of(SILVER_V1)),
                        version(SILVER_V1, "program-001", "MEMBER", 1, List.of(BRONZE_V1)),
                        version(BRONZE_V1, "program-001", "MEMBER", 1, List.of(EVENTS_V1)),
                        version(EVENTS_V1, "program-001", "EVENT", 1, List.of(TRAVEL_V1)),
                        version(TRAVEL_V1, "program-001", "MEMBER", 1, List.of())))));

        // The cross-tenant catalog declares the same wire shape but is owned by tenant-002: a caller in
        // tenant-001 that reaches it must see the version as absent, never as someone else's version.
        specs.add(new FixtureSpec(CATALOG_TENANT_002, ReferenceFixtureRole.VERSION_CATALOG, "2",
                catalog("catalog-tenant-002", "tenant-002", List.of(
                        version(PLATINUM_V1, "program-009", "MEMBER", 1, List.of())))));

        specs.add(new FixtureSpec(DATASCOPE_PRIMARY, ReferenceFixtureRole.DATA_SCOPE, "1",
                map("scopeId", "scope-primary-001", "available", true,
                        "intersectionHash", hashOf("intersection:scope-primary-001").value(),
                        "narrowing", List.of("scope-primary-001"))));
        specs.add(new FixtureSpec(DATASCOPE_NARROW, ReferenceFixtureRole.DATA_SCOPE, "2",
                map("scopeId", "scope-narrow-002", "available", true,
                        "intersectionHash", hashOf("intersection:scope-primary-001+scope-narrow-002").value(),
                        "narrowing", List.of("scope-primary-001", "scope-narrow-002"))));
        specs.add(new FixtureSpec(DATASCOPE_EMPTY, ReferenceFixtureRole.DATA_SCOPE, "3",
                map("scopeId", "scope-empty-003", "available", true,
                        "intersectionHash", hashOf("intersection:empty").value(),
                        "narrowing", List.of())));

        specs.add(new FixtureSpec(PROGRAM_ALLOWED, ReferenceFixtureRole.PROGRAM_POLICY, "1",
                map("policyId", "program-policy-allowed-001", "defaultAllowed", true,
                        "relations", List.of(map("callerProgramId", "program-001",
                                "referencedProgramId", "program-001")))));
        specs.add(new FixtureSpec(PROGRAM_DENIED, ReferenceFixtureRole.PROGRAM_POLICY, "2",
                map("policyId", "program-policy-denied-002", "defaultAllowed", false,
                        "relations", List.of())));

        specs.add(new FixtureSpec(POLICY_BOUNDED, ReferenceFixtureRole.REFERENCE_POLICY, "1",
                map("policyId", "reference-policy-bounded-001", "maxDepth", 5,
                        "maxDistinctVersions", 20, "maxExpandedComplexity", 200)));
        specs.add(new FixtureSpec(POLICY_STRICT, ReferenceFixtureRole.REFERENCE_POLICY, "2",
                map("policyId", "reference-policy-strict-002", "maxDepth", 2,
                        "maxDistinctVersions", 3, "maxExpandedComplexity", 10)));

        specs.add(new FixtureSpec(DIRECTORY_MAIN, ReferenceFixtureRole.DISCOVERY_DIRECTORY, "1",
                directory("directory-main-001", List.of(
                        audience("audience-loyalty-gold", "Gold Members", true, List.of(GOLD_V1)),
                        audience("audience-events-active", "Active Event Members", true, List.of(EVENTS_V1))))));
        specs.add(new FixtureSpec(DIRECTORY_AMBIGUOUS, ReferenceFixtureRole.DISCOVERY_DIRECTORY, "2",
                directory("directory-ambiguous-002", List.of(
                        audience("audience-loyalty-gold", "Gold Members", true, List.of(GOLD_V1, GOLD_V2)),
                        audience("audience-loyalty-gold-upper", "Gold Members", true, List.of(GOLD_V1))))));
        specs.add(new FixtureSpec(DIRECTORY_DENIED, ReferenceFixtureRole.DISCOVERY_DIRECTORY, "3",
                directory("directory-denied-003", List.of(
                        audience("audience-loyalty-platinum", "Platinum Members", false, List.of())))));
        specs.add(new FixtureSpec(DIRECTORY_EMPTY, ReferenceFixtureRole.DISCOVERY_DIRECTORY, "4",
                directory("directory-empty-004", List.of())));

        // Membership is recorded as a hash and a count only: a corpus asset never records who is in an
        // audience, so the fixture cannot become a second, unreviewed copy of the member universe.
        specs.add(new FixtureSpec(MEMBERSHIP_MAIN, ReferenceFixtureRole.MEMBERSHIP_DATASET, "1",
                map("datasetId", "membership-main-001", "entries", List.of(
                        membershipEntry(GOLD_V1, 12, hashOf("members:av-gold-001")),
                        membershipEntry(GOLD_V2, 14, hashOf("members:av-gold-002"))))));

        specs.add(new FixtureSpec(RETENTION_HOLD, ReferenceFixtureRole.RETENTION_POLICY, "1",
                map("policyId", "retention-policy-hold-001", "permitsDeletionWhenUnheld", false)));
        specs.add(new FixtureSpec(RETENTION_RELEASE, ReferenceFixtureRole.RETENTION_POLICY, "2",
                map("policyId", "retention-policy-release-002", "permitsDeletionWhenUnheld", true)));

        specs.add(new FixtureSpec(CONTEXT_PRIMARY, ReferenceFixtureRole.EXECUTION_CONTEXT, "1",
                context("tenant-001", "program-001", "scope-primary-001")));
        specs.add(new FixtureSpec(CONTEXT_NARROW, ReferenceFixtureRole.EXECUTION_CONTEXT, "2",
                context("tenant-001", "program-002", "scope-narrow-002")));
        return List.copyOf(specs);
    }

    // ------------------------------------------------------------------ cases

    static List<CaseBlueprint> cases() {
        List<CaseBlueprint> blueprints = new ArrayList<>();

        // --- 1. EXACT_PIN_INTEGRITY -----------------------------------------------------------------
        blueprints.add(documentCase("case-ref-01-pin-exact-clean", ReferenceCategory.EXACT_PIN_INTEGRITY,
                RiskLevel.HIGH, "A reference that names exactly one immutable published version is pinned "
                        + "to that version and validates cleanly.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_PRIMARY, PROGRAM_ALLOWED, POLICY_BOUNDED, CONTEXT_PRIMARY),
                document("node-ref-01-root", group("node-ref-01", "AND", reference("node-ref-01-gold", GOLD_V1))),
                List.of(site("node-ref-01-gold", GOLD_V1, SubjectKind.MEMBER)), null, null,
                clean("case-ref-01-pin-exact-clean", document("node-ref-01-root",
                        group("node-ref-01", "AND", reference("node-ref-01-gold", GOLD_V1))),
                        graph(List.of(GOLD_V1), 1, 1))));

        ReferencePin stalePin = new ReferencePin(GOLD_V1.audienceId(), GOLD_V1.versionId(),
                hashOf("stale:av-gold-001"));
        blueprints.add(documentCase("case-ref-02-pin-stale-hash", ReferenceCategory.EXACT_PIN_INTEGRITY,
                RiskLevel.HIGH, "A pin whose exact content hash no longer matches the version's bytes is "
                        + "refused as a conflict instead of resolving to the changed version.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_PRIMARY, PROGRAM_ALLOWED, POLICY_BOUNDED, CONTEXT_PRIMARY),
                document("node-ref-02-root", group("node-ref-02", "AND", reference("node-ref-02-gold", stalePin))),
                List.of(site("node-ref-02-gold", stalePin, SubjectKind.MEMBER)), null, null,
                rejected("case-ref-02-pin-stale-hash", VERSION_CONFLICT)));

        ReferencePin unknownPin = new ReferencePin("audience-loyalty-gold", "av-gold-999",
                hashOf("av-gold-999"));
        blueprints.add(documentCase("case-ref-03-pin-unknown-version", ReferenceCategory.EXACT_PIN_INTEGRITY,
                RiskLevel.HIGH, "A pin naming a version identity the trusted catalog does not hold is "
                        + "reported as not found, without disclosing whether it ever existed.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_PRIMARY, PROGRAM_ALLOWED, POLICY_BOUNDED, CONTEXT_PRIMARY),
                document("node-ref-03-root", group("node-ref-03", "AND", reference("node-ref-03-gold", unknownPin))),
                List.of(site("node-ref-03-gold", unknownPin, SubjectKind.MEMBER)), null, null,
                rejected("case-ref-03-pin-unknown-version", VERSION_NOT_FOUND)));

        // --- 2. CYCLE_REJECTION ---------------------------------------------------------------------
        blueprints.add(documentCase("case-ref-04-cycle-self", ReferenceCategory.CYCLE_REJECTION,
                RiskLevel.HIGH, "A candidate edit whose document reaches its own root version through a "
                        + "self reference is rejected as a cycle.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_PRIMARY, PROGRAM_ALLOWED, POLICY_BOUNDED, CONTEXT_PRIMARY),
                document("node-ref-04-root", group("node-ref-04", "AND", reference("node-ref-04-gold", GOLD_V1))),
                List.of(site("node-ref-04-gold", GOLD_V1, SubjectKind.MEMBER)), GOLD_V1, null,
                rejected("case-ref-04-cycle-self", CYCLE_DETECTED)));

        blueprints.add(documentCase("case-ref-05-cycle-two-node", ReferenceCategory.CYCLE_REJECTION,
                RiskLevel.HIGH, "Two referenced versions that depend on each other and on the document's "
                        + "root are rejected as a two-node cycle before any preview or apply.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_PRIMARY, PROGRAM_ALLOWED, POLICY_BOUNDED, CONTEXT_PRIMARY),
                document("node-ref-05-root", group("node-ref-05", "AND",
                        reference("node-ref-05-gold", GOLD_V1), reference("node-ref-05-silver", SILVER_V1))),
                List.of(site("node-ref-05-gold", GOLD_V1, SubjectKind.MEMBER),
                        site("node-ref-05-silver", SILVER_V1, SubjectKind.MEMBER)), null, null,
                rejected("case-ref-05-cycle-two-node", CYCLE_DETECTED)));

        blueprints.add(documentCase("case-ref-06-cycle-deep", ReferenceCategory.CYCLE_REJECTION,
                RiskLevel.HIGH, "A three-node ring among transitively reachable versions is rejected as a "
                        + "deep cycle rather than admitted as a bounded graph.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_PRIMARY, PROGRAM_ALLOWED, POLICY_BOUNDED, CONTEXT_PRIMARY),
                document("node-ref-06-root", group("node-ref-06", "AND",
                        reference("node-ref-06-gold", GOLD_V1), reference("node-ref-06-silver", SILVER_V1),
                        reference("node-ref-06-bronze", BRONZE_V1))),
                List.of(site("node-ref-06-gold", GOLD_V1, SubjectKind.MEMBER),
                        site("node-ref-06-silver", SILVER_V1, SubjectKind.MEMBER),
                        site("node-ref-06-bronze", BRONZE_V1, SubjectKind.MEMBER)), null, null,
                rejected("case-ref-06-cycle-deep", CYCLE_DETECTED)));

        blueprints.add(documentCase("case-ref-07-diamond-not-cycle", ReferenceCategory.CYCLE_REJECTION,
                RiskLevel.MEDIUM, "Two independent paths reaching one shared version form a diamond, which "
                        + "is a clean graph and never a cycle.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_PRIMARY, PROGRAM_ALLOWED, POLICY_BOUNDED, CONTEXT_PRIMARY),
                document("node-ref-07-root", group("node-ref-07", "AND",
                        reference("node-ref-07-gold", GOLD_V1), reference("node-ref-07-silver", SILVER_V1),
                        reference("node-ref-07-bronze", BRONZE_V1), reference("node-ref-07-events", EVENTS_V1))),
                List.of(site("node-ref-07-gold", GOLD_V1, SubjectKind.MEMBER),
                        site("node-ref-07-silver", SILVER_V1, SubjectKind.MEMBER),
                        site("node-ref-07-bronze", BRONZE_V1, SubjectKind.MEMBER),
                        site("node-ref-07-events", EVENTS_V1, SubjectKind.EVENT)), null, null,
                clean("case-ref-07-diamond-not-cycle", document("node-ref-07-root", group("node-ref-07", "AND",
                                reference("node-ref-07-gold", GOLD_V1), reference("node-ref-07-silver", SILVER_V1),
                                reference("node-ref-07-bronze", BRONZE_V1),
                                reference("node-ref-07-events", EVENTS_V1))),
                        graph(List.of(GOLD_V1, SILVER_V1, BRONZE_V1, EVENTS_V1), 3, 6))));

        // --- 3. GRAPH_LIMIT_ENFORCEMENT -------------------------------------------------------------
        blueprints.add(documentCase("case-ref-08-depth-exceeded", ReferenceCategory.GRAPH_LIMIT_ENFORCEMENT,
                RiskLevel.HIGH, "A dependency chain deeper than the bounded policy maximum is stopped by "
                        + "the depth limit before any side effect.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_PRIMARY, PROGRAM_ALLOWED, POLICY_STRICT, CONTEXT_PRIMARY),
                document("node-ref-08-root", group("node-ref-08", "AND",
                        reference("node-ref-08-gold", GOLD_V1), reference("node-ref-08-silver", SILVER_V1),
                        reference("node-ref-08-bronze", BRONZE_V1), reference("node-ref-08-events", EVENTS_V1))),
                List.of(site("node-ref-08-gold", GOLD_V1, SubjectKind.MEMBER),
                        site("node-ref-08-silver", SILVER_V1, SubjectKind.MEMBER),
                        site("node-ref-08-bronze", BRONZE_V1, SubjectKind.MEMBER),
                        site("node-ref-08-events", EVENTS_V1, SubjectKind.EVENT)), null, null,
                rejected("case-ref-08-depth-exceeded", DEPTH_EXCEEDED)));

        blueprints.add(documentCase("case-ref-09-distinct-versions-exceeded",
                ReferenceCategory.GRAPH_LIMIT_ENFORCEMENT, RiskLevel.HIGH,
                "A document depending on more distinct versions than the bounded policy allows is stopped "
                        + "by the distinct-version limit.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_PRIMARY, PROGRAM_ALLOWED, POLICY_STRICT, CONTEXT_PRIMARY),
                document("node-ref-09-root", group("node-ref-09", "AND",
                        reference("node-ref-09-gold", GOLD_V1), reference("node-ref-09-silver", SILVER_V1),
                        reference("node-ref-09-bronze", BRONZE_V1), reference("node-ref-09-events", EVENTS_V1),
                        reference("node-ref-09-travel", TRAVEL_V1))),
                List.of(site("node-ref-09-gold", GOLD_V1, SubjectKind.MEMBER),
                        site("node-ref-09-silver", SILVER_V1, SubjectKind.MEMBER),
                        site("node-ref-09-bronze", BRONZE_V1, SubjectKind.MEMBER),
                        site("node-ref-09-events", EVENTS_V1, SubjectKind.EVENT),
                        site("node-ref-09-travel", TRAVEL_V1, SubjectKind.MEMBER)), null, null,
                rejected("case-ref-09-distinct-versions-exceeded", DISTINCT_VERSIONS_EXCEEDED)));

        blueprints.add(documentCase("case-ref-10-expanded-complexity-exceeded",
                ReferenceCategory.GRAPH_LIMIT_ENFORCEMENT, RiskLevel.HIGH,
                "A graph whose summed expanded complexity exceeds the bounded policy maximum is stopped by "
                        + "the complexity limit.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_PRIMARY, PROGRAM_ALLOWED, POLICY_STRICT, CONTEXT_PRIMARY),
                document("node-ref-10-root", group("node-ref-10", "AND",
                        reference("node-ref-10-gold", GOLD_V1), reference("node-ref-10-silver", SILVER_V1),
                        reference("node-ref-10-bronze", BRONZE_V1))),
                List.of(site("node-ref-10-gold", GOLD_V1, SubjectKind.MEMBER),
                        site("node-ref-10-silver", SILVER_V1, SubjectKind.MEMBER),
                        site("node-ref-10-bronze", BRONZE_V1, SubjectKind.MEMBER)), null, null,
                rejected("case-ref-10-expanded-complexity-exceeded", COMPLEXITY_EXCEEDED)));

        blueprints.add(documentCase("case-ref-11-depth-and-fanout-clean",
                ReferenceCategory.GRAPH_LIMIT_ENFORCEMENT, RiskLevel.MEDIUM,
                "A five-deep fanout of five distinct versions sits exactly at the bounded maximum and must "
                        + "still validate cleanly, so the bound is proven inclusive.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_PRIMARY, PROGRAM_ALLOWED, POLICY_BOUNDED, CONTEXT_PRIMARY),
                document("node-ref-11-root", group("node-ref-11", "AND",
                        reference("node-ref-11-gold", GOLD_V1), reference("node-ref-11-silver", SILVER_V1),
                        reference("node-ref-11-bronze", BRONZE_V1), reference("node-ref-11-events", EVENTS_V1),
                        reference("node-ref-11-travel", TRAVEL_V1))),
                List.of(site("node-ref-11-gold", GOLD_V1, SubjectKind.MEMBER),
                        site("node-ref-11-silver", SILVER_V1, SubjectKind.MEMBER),
                        site("node-ref-11-bronze", BRONZE_V1, SubjectKind.MEMBER),
                        site("node-ref-11-events", EVENTS_V1, SubjectKind.EVENT),
                        site("node-ref-11-travel", TRAVEL_V1, SubjectKind.MEMBER)), null, null,
                clean("case-ref-11-depth-and-fanout-clean", document("node-ref-11-root", group("node-ref-11", "AND",
                                reference("node-ref-11-gold", GOLD_V1), reference("node-ref-11-silver", SILVER_V1),
                                reference("node-ref-11-bronze", BRONZE_V1),
                                reference("node-ref-11-events", EVENTS_V1),
                                reference("node-ref-11-travel", TRAVEL_V1))),
                        graph(List.of(GOLD_V1, SILVER_V1, BRONZE_V1, EVENTS_V1, TRAVEL_V1), 5, 5))));

        // --- 4. DATASCOPE_INTERSECTION --------------------------------------------------------------
        blueprints.add(documentCase("case-ref-12-datascope-narrowed-clean",
                ReferenceCategory.DATASCOPE_INTERSECTION, RiskLevel.HIGH,
                "The effective scope is the trusted intersection of the caller scope and the referenced "
                        + "scope, and the narrowed reference still validates cleanly.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_NARROW, PROGRAM_ALLOWED, POLICY_BOUNDED, CONTEXT_NARROW),
                document("node-ref-12-root", group("node-ref-12", "AND", reference("node-ref-12-gold", GOLD_V1))),
                List.of(site("node-ref-12-gold", GOLD_V1, SubjectKind.MEMBER)), null, null,
                clean("case-ref-12-datascope-narrowed-clean", document("node-ref-12-root",
                        group("node-ref-12", "AND", reference("node-ref-12-gold", GOLD_V1))),
                        graph(List.of(GOLD_V1), 1, 1))));

        blueprints.add(documentCase("case-ref-13-datascope-empty", ReferenceCategory.DATASCOPE_INTERSECTION,
                RiskLevel.HIGH, "An empty trusted intersection fails closed rather than widening the "
                        + "reference to the caller scope.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_EMPTY, PROGRAM_ALLOWED, POLICY_BOUNDED, CONTEXT_PRIMARY),
                document("node-ref-13-root", group("node-ref-13", "AND", reference("node-ref-13-gold", GOLD_V1))),
                List.of(site("node-ref-13-gold", GOLD_V1, SubjectKind.MEMBER)), null, null,
                rejected("case-ref-13-datascope-empty", DATASCOPE_INTERSECTION_EMPTY)));

        blueprints.add(documentCase("case-ref-14-datascope-unavailable",
                ReferenceCategory.DATASCOPE_INTERSECTION, RiskLevel.HIGH,
                "A scope port that cannot be reached fails closed instead of falling back to a copied or "
                        + "concatenated scope.",
                documentFixtures(CATALOG_MAIN, null, PROGRAM_ALLOWED, POLICY_BOUNDED, CONTEXT_PRIMARY),
                document("node-ref-14-root", group("node-ref-14", "AND", reference("node-ref-14-gold", GOLD_V1))),
                List.of(site("node-ref-14-gold", GOLD_V1, SubjectKind.MEMBER)), null, null,
                rejected("case-ref-14-datascope-unavailable", DATASCOPE_UNAVAILABLE)));

        // --- 5. AUTHORIZATION_BOUNDARY --------------------------------------------------------------
        blueprints.add(documentCase("case-ref-15-denied-segment", ReferenceCategory.AUTHORIZATION_BOUNDARY,
                RiskLevel.HIGH, "A reference the trusted authorization boundary refuses is reported as "
                        + "not accessible, without stating which permission was missing.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_PRIMARY, PROGRAM_DENIED, POLICY_BOUNDED, CONTEXT_PRIMARY),
                document("node-ref-15-root", group("node-ref-15", "AND", reference("node-ref-15-gold", GOLD_V1))),
                List.of(site("node-ref-15-gold", GOLD_V1, SubjectKind.MEMBER)), null, null,
                rejected("case-ref-15-denied-segment", RESOURCE_NOT_ACCESSIBLE)));

        blueprints.add(documentCase("case-ref-16-cross-program", ReferenceCategory.AUTHORIZATION_BOUNDARY,
                RiskLevel.HIGH, "A caller program the host relation policy does not allow to reach the "
                        + "referenced program is refused from trusted facts only.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_PRIMARY, PROGRAM_DENIED, POLICY_BOUNDED, CONTEXT_PRIMARY),
                document("node-ref-16-root", group("node-ref-16", "AND", reference("node-ref-16-gold", GOLD_V1))),
                List.of(site("node-ref-16-gold", GOLD_V1, SubjectKind.MEMBER)), null, null,
                rejected("case-ref-16-cross-program", PROGRAM_NOT_ALLOWED)));

        blueprints.add(documentCase("case-ref-17-cross-tenant", ReferenceCategory.AUTHORIZATION_BOUNDARY,
                RiskLevel.HIGH, "A version owned by another tenant is indistinguishable from one that does "
                        + "not exist: both produce the same non-enumerating not-found result.",
                documentFixtures(CATALOG_TENANT_002, DATASCOPE_PRIMARY, PROGRAM_ALLOWED, POLICY_BOUNDED,
                        CONTEXT_PRIMARY),
                document("node-ref-17-root", group("node-ref-17", "AND",
                        reference("node-ref-17-platinum", PLATINUM_V1))),
                List.of(site("node-ref-17-platinum", PLATINUM_V1, SubjectKind.MEMBER)), null, null,
                rejected("case-ref-17-cross-tenant", VERSION_NOT_FOUND)));

        blueprints.add(documentCase("case-ref-18-missing-context", ReferenceCategory.AUTHORIZATION_BOUNDARY,
                RiskLevel.HIGH, "A reference-bearing document with no trusted resolution context declares "
                        + "no catalog, so the reference fails closed rather than being resolved by guesswork.",
                onlyContextFixtures(CONTEXT_PRIMARY),
                document("node-ref-18-root", group("node-ref-18", "AND", reference("node-ref-18-gold", GOLD_V1))),
                List.of(site("node-ref-18-gold", GOLD_V1, SubjectKind.MEMBER)), null, null,
                rejected("case-ref-18-missing-context", CONTEXT_MISSING)));

        blueprints.add(documentCase("case-ref-19-subject-mismatch", ReferenceCategory.AUTHORIZATION_BOUNDARY,
                RiskLevel.HIGH, "A reference evaluated in an order scope that names a member-subject "
                        + "version is refused as a subject mismatch.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_PRIMARY, PROGRAM_ALLOWED, POLICY_BOUNDED, CONTEXT_PRIMARY),
                document("node-ref-19-root", group("node-ref-19", "AND",
                        exists("node-ref-19-exists", "orders", reference("node-ref-19-gold", GOLD_V1)))),
                List.of(site("node-ref-19-gold", GOLD_V1, SubjectKind.ORDER)), null, null,
                rejected("case-ref-19-subject-mismatch", SUBJECT_MISMATCH)));

        // --- 6. NESTED_BOOLEAN_AND_DUPLICATES -------------------------------------------------------
        blueprints.add(documentCase("case-ref-20-nested-boolean", ReferenceCategory.NESTED_BOOLEAN_AND_DUPLICATES,
                RiskLevel.HIGH, "Reference sites survive nested OR, NOT and EXISTS composition and the "
                        + "subject scope follows the tree shape rather than the document root.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_PRIMARY, PROGRAM_ALLOWED, POLICY_BOUNDED, CONTEXT_PRIMARY),
                document("node-ref-20-root", group("node-ref-20", "OR",
                        not("node-ref-20-not", reference("node-ref-20-silver", SILVER_V1)),
                        exists("node-ref-20-exists", "orders", reference("node-ref-20-events", EVENTS_V1)))),
                List.of(site("node-ref-20-silver", SILVER_V1, SubjectKind.MEMBER),
                        site("node-ref-20-events", EVENTS_V1, SubjectKind.ORDER)), null, null,
                clean("case-ref-20-nested-boolean", document("node-ref-20-root", group("node-ref-20", "OR",
                                not("node-ref-20-not", reference("node-ref-20-silver", SILVER_V1)),
                                exists("node-ref-20-exists", "orders",
                                        reference("node-ref-20-events", EVENTS_V1)))),
                        graph(List.of(SILVER_V1, EVENTS_V1), 2, 3))));

        blueprints.add(documentCase("case-ref-21-exists-scope", ReferenceCategory.NESTED_BOOLEAN_AND_DUPLICATES,
                RiskLevel.HIGH, "A reference inside EXISTS is scoped to the relation target, so a "
                        + "member-subject version referenced from an order scope is the mismatch this "
                        + "shape exposes rather than a clean graph.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_PRIMARY, PROGRAM_ALLOWED, POLICY_BOUNDED, CONTEXT_PRIMARY),
                document("node-ref-21-root", group("node-ref-21", "AND",
                        exists("node-ref-21-exists", "orders", reference("node-ref-21-events", EVENTS_V1)))),
                List.of(site("node-ref-21-events", EVENTS_V1, SubjectKind.ORDER)), null, null,
                clean("case-ref-21-exists-scope", document("node-ref-21-root", group("node-ref-21", "AND",
                                exists("node-ref-21-exists", "orders",
                                        reference("node-ref-21-events", EVENTS_V1)))),
                        graph(List.of(EVENTS_V1), 2, 2))));

        blueprints.add(documentCase("case-ref-22-duplicate-references",
                ReferenceCategory.NESTED_BOOLEAN_AND_DUPLICATES, RiskLevel.MEDIUM,
                "Three sites naming two distinct pins are accounted for once each, so a duplicated "
                        + "reference is de-duplicated rather than counted twice.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_PRIMARY, PROGRAM_ALLOWED, POLICY_BOUNDED, CONTEXT_PRIMARY),
                document("node-ref-22-root", group("node-ref-22", "AND",
                        reference("node-ref-22-gold-first", GOLD_V1),
                        reference("node-ref-22-gold-second", GOLD_V1),
                        reference("node-ref-22-silver", SILVER_V1))),
                List.of(site("node-ref-22-gold-first", GOLD_V1, SubjectKind.MEMBER),
                        site("node-ref-22-gold-second", GOLD_V1, SubjectKind.MEMBER),
                        site("node-ref-22-silver", SILVER_V1, SubjectKind.MEMBER)), null, null,
                clean("case-ref-22-duplicate-references", document("node-ref-22-root", group("node-ref-22", "AND",
                                reference("node-ref-22-gold-first", GOLD_V1),
                                reference("node-ref-22-gold-second", GOLD_V1),
                                reference("node-ref-22-silver", SILVER_V1))),
                        graph(List.of(GOLD_V1, SILVER_V1), 1, 2))));

        blueprints.add(documentCase("case-ref-23-stable-refinement",
                ReferenceCategory.NESTED_BOOLEAN_AND_DUPLICATES, RiskLevel.HIGH,
                "A refined document that becomes one immutable root version keeps its reference sites by "
                        + "stable node identity and adds a new exact pin.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_PRIMARY, PROGRAM_ALLOWED, POLICY_BOUNDED, CONTEXT_PRIMARY),
                document("node-ref-23-root", group("node-ref-23", "AND",
                        reference("node-ref-23-silver", SILVER_V1), reference("node-ref-23-events", EVENTS_V1))),
                List.of(site("node-ref-23-silver", SILVER_V1, SubjectKind.MEMBER),
                        site("node-ref-23-events", EVENTS_V1, SubjectKind.EVENT)), GOLD_V2, null,
                clean("case-ref-23-stable-refinement", document("node-ref-23-root", group("node-ref-23", "AND",
                                reference("node-ref-23-silver", SILVER_V1),
                                reference("node-ref-23-events", EVENTS_V1))),
                        graph(List.of(SILVER_V1, EVENTS_V1), 1, 2))));

        // --- 7. UNIQUE_RESOLUTION_AND_PINNING -------------------------------------------------------
        blueprints.add(resolutionCase("case-ref-24-resolve-unique-identity",
                ReferenceCategory.UNIQUE_RESOLUTION_AND_PINNING, RiskLevel.HIGH,
                "A request matching one audience with one eligible immutable published version is pinned to "
                        + "that exact version and records its resolution evidence.",
                directoryFixtures(DIRECTORY_MAIN, CONTEXT_PRIMARY),
                "reference the loyalty gold audience", List.of("audience-loyalty-gold"), null, null,
                resolved("case-ref-24-resolve-unique-identity", GOLD_V1)));

        blueprints.add(resolutionCase("case-ref-25-resolve-explicit-version",
                ReferenceCategory.UNIQUE_RESOLUTION_AND_PINNING, RiskLevel.HIGH,
                "A host-applied exact-version answer resolves deterministically to that immutable pin "
                        + "rather than re-asking.",
                directoryFixtures(DIRECTORY_MAIN, CONTEXT_PRIMARY),
                "reference the loyalty gold audience", List.of("audience-loyalty-gold"), null, "av-gold-001",
                resolved("case-ref-25-resolve-explicit-version", GOLD_V1)));

        blueprints.add(resolutionCase("case-ref-26-resolve-unique-name",
                ReferenceCategory.UNIQUE_RESOLUTION_AND_PINNING, RiskLevel.MEDIUM,
                "A unique audience name resolves once to one immutable published version and exposes the "
                        + "pin it chose.",
                directoryFixtures(DIRECTORY_MAIN, CONTEXT_PRIMARY),
                "reference the audience known as Active Event Members", List.of("audience-events-active"),
                null, null, resolved("case-ref-26-resolve-unique-name", EVENTS_V1)));

        // --- 8. AMBIGUOUS_AUDIENCE_OR_VERSION -------------------------------------------------------
        blueprints.add(resolutionCase("case-ref-27-ambiguous-audiences",
                ReferenceCategory.AMBIGUOUS_AUDIENCE_OR_VERSION, RiskLevel.HIGH,
                "Two visible audiences matching one wording produce exactly one structured audience "
                        + "question instead of a guess.",
                directoryFixtures(DIRECTORY_AMBIGUOUS, CONTEXT_PRIMARY),
                "reference the gold member audience", List.of("audience-loyalty-gold",
                        "audience-loyalty-gold-upper"), null, null,
                needsInput("case-ref-27-ambiguous-audiences", SELECT_AUDIENCE)));

        blueprints.add(resolutionCase("case-ref-28-duplicate-names",
                ReferenceCategory.AMBIGUOUS_AUDIENCE_OR_VERSION, RiskLevel.HIGH,
                "Two audiences sharing one display name are ambiguous by name, so the resolver asks which "
                        + "audience was meant rather than choosing by discovery order.",
                directoryFixtures(DIRECTORY_AMBIGUOUS, CONTEXT_PRIMARY),
                "reference the audience named Gold Members", List.of("audience-loyalty-gold",
                        "audience-loyalty-gold-upper"), null, null,
                needsInput("case-ref-28-duplicate-names", SELECT_AUDIENCE)));

        blueprints.add(resolutionCase("case-ref-29-ambiguous-versions",
                ReferenceCategory.AMBIGUOUS_AUDIENCE_OR_VERSION, RiskLevel.HIGH,
                "One audience with two eligible immutable published versions produces exactly one "
                        + "structured version question.",
                directoryFixtures(DIRECTORY_AMBIGUOUS, CONTEXT_PRIMARY),
                "reference the loyalty gold audience", List.of("audience-loyalty-gold"), null, null,
                needsInput("case-ref-29-ambiguous-versions", SELECT_VERSION)));

        // --- 9. DYNAMIC_WORDING ---------------------------------------------------------------------
        blueprints.add(resolutionCase("case-ref-30-current-wording-resolved",
                ReferenceCategory.DYNAMIC_WORDING, RiskLevel.HIGH,
                "A current-audience wording is resolved once to an immutable pin and the pin is exposed, "
                        + "so the decision never stays dynamic.",
                directoryFixtures(DIRECTORY_MAIN, CONTEXT_PRIMARY),
                "reference the current gold member audience", List.of("audience-loyalty-gold"), null, null,
                resolved("case-ref-30-current-wording-resolved", GOLD_V1)));

        blueprints.add(resolutionCase("case-ref-31-latest-wording-resolved",
                ReferenceCategory.DYNAMIC_WORDING, RiskLevel.HIGH,
                "A latest-audience wording over one eligible version resolves to that exact immutable "
                        + "version instead of remaining a moving pointer.",
                directoryFixtures(DIRECTORY_MAIN, CONTEXT_PRIMARY),
                "reference the latest active event audience", List.of("audience-events-active"), null, null,
                resolved("case-ref-31-latest-wording-resolved", EVENTS_V1)));

        blueprints.add(resolutionCase("case-ref-32-latest-wording-needs-version",
                ReferenceCategory.DYNAMIC_WORDING, RiskLevel.HIGH,
                "A latest-audience wording over two eligible versions asks one version question, because "
                        + "latest can never be resolved by preferring a newer discovery result.",
                directoryFixtures(DIRECTORY_AMBIGUOUS, CONTEXT_PRIMARY),
                "reference the latest gold member audience", List.of("audience-loyalty-gold"), null, null,
                needsInput("case-ref-32-latest-wording-needs-version", SELECT_VERSION)));

        // --- 10. DENIED_AND_UNAVAILABLE_TARGET ------------------------------------------------------
        blueprints.add(resolutionCase("case-ref-33-denied-target",
                ReferenceCategory.DENIED_AND_UNAVAILABLE_TARGET, RiskLevel.HIGH,
                "A visible but inaccessible candidate is reported as denied with a bounded reason and no "
                        + "existence disclosure beyond what discovery showed.",
                directoryFixtures(DIRECTORY_DENIED, CONTEXT_PRIMARY),
                "reference the platinum member audience", List.of("audience-loyalty-platinum"), null, null,
                denied("case-ref-33-denied-target", REFERENCE_UNAVAILABLE)));

        blueprints.add(resolutionCase("case-ref-34-unknown-target",
                ReferenceCategory.DENIED_AND_UNAVAILABLE_TARGET, RiskLevel.HIGH,
                "A wording matching nothing visible is unsupported rather than denied, so there is nothing "
                        + "to use and nothing to disclose.",
                directoryFixtures(DIRECTORY_EMPTY, CONTEXT_PRIMARY),
                "reference the audience named Unlisted Segment", List.of("audience-unlisted-segment"),
                null, null, unsupported("case-ref-34-unknown-target", REFERENCE_UNAVAILABLE)));

        blueprints.add(resolutionCase("case-ref-35-cross-tenant-indistinguishable",
                ReferenceCategory.DENIED_AND_UNAVAILABLE_TARGET, RiskLevel.HIGH,
                "A version only another tenant can see is indistinguishable from one that does not exist: "
                        + "both produce the same bounded unavailable result.",
                directoryFixtures(DIRECTORY_MAIN, CONTEXT_PRIMARY),
                "reference the platinum member audience", List.of("audience-loyalty-platinum"), null, null,
                unsupported("case-ref-35-cross-tenant-indistinguishable", REFERENCE_UNAVAILABLE)));

        // --- 11. EXECUTION_TIME_DRIFT ----------------------------------------------------------------
        blueprints.add(documentCase("case-ref-36-drift-withdrawn-version", ReferenceCategory.EXECUTION_TIME_DRIFT,
                RiskLevel.HIGH, "A version withdrawn between two steps is observed as unavailable by a "
                        + "genuine re-check rather than replayed from the first decision.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_PRIMARY, PROGRAM_ALLOWED, POLICY_BOUNDED, CONTEXT_PRIMARY),
                document("node-ref-36-root", group("node-ref-36", "AND", reference("node-ref-36-gold", GOLD_V1))),
                List.of(site("node-ref-36-gold", GOLD_V1, SubjectKind.MEMBER)), null,
                new ReferenceRecheck(ReferenceRecheckKind.WITHDRAW_AVAILABILITY, null),
                cleanWithRecheck("case-ref-36-drift-withdrawn-version", document("node-ref-36-root",
                                group("node-ref-36", "AND", reference("node-ref-36-gold", GOLD_V1))),
                        graph(List.of(GOLD_V1), 1, 1),
                        new ReferenceExpectedRecheck(ReferenceRecheckOutcome.REJECTED,
                                List.of(VERSION_UNAVAILABLE)))));

        blueprints.add(documentCase("case-ref-37-drift-revoked-permission",
                ReferenceCategory.EXECUTION_TIME_DRIFT, RiskLevel.HIGH,
                "A permission revoked between two steps is observed by a re-authorization, so the earlier "
                        + "resolution is never treated as permanent authority.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_PRIMARY, PROGRAM_ALLOWED, POLICY_BOUNDED, CONTEXT_PRIMARY),
                document("node-ref-37-root", group("node-ref-37", "AND", reference("node-ref-37-gold", GOLD_V1))),
                List.of(site("node-ref-37-gold", GOLD_V1, SubjectKind.MEMBER)), null,
                new ReferenceRecheck(ReferenceRecheckKind.DENY_AUTHORIZATION, null),
                cleanWithRecheck("case-ref-37-drift-revoked-permission", document("node-ref-37-root",
                                group("node-ref-37", "AND", reference("node-ref-37-gold", GOLD_V1))),
                        graph(List.of(GOLD_V1), 1, 1),
                        new ReferenceExpectedRecheck(ReferenceRecheckOutcome.REJECTED,
                                List.of(RESOURCE_NOT_ACCESSIBLE)))));

        blueprints.add(documentCase("case-ref-38-drift-released-hold", ReferenceCategory.EXECUTION_TIME_DRIFT,
                RiskLevel.HIGH, "A released retention hold is observed while the permission and version "
                        + "still hold, so the retention evidence no longer protects the bytes.",
                documentFixtures(CATALOG_MAIN, DATASCOPE_PRIMARY, PROGRAM_ALLOWED, POLICY_BOUNDED, CONTEXT_PRIMARY),
                document("node-ref-38-root", group("node-ref-38", "AND", reference("node-ref-38-gold", GOLD_V1))),
                List.of(site("node-ref-38-gold", GOLD_V1, SubjectKind.MEMBER)), null,
                new ReferenceRecheck(ReferenceRecheckKind.RELEASE_RETENTION_HOLD, "hold-ref-av-gold-001"),
                cleanWithRecheck("case-ref-38-drift-released-hold", document("node-ref-38-root",
                                group("node-ref-38", "AND", reference("node-ref-38-gold", GOLD_V1))),
                        graph(List.of(GOLD_V1), 1, 1),
                        new ReferenceExpectedRecheck(ReferenceRecheckOutcome.HOLD_RELEASED, List.of()))));

        // --- 12. RETENTION_INTEGRITY -----------------------------------------------------------------
        blueprints.add(retentionCase("case-ref-39-hold-keeps-version-alive",
                ReferenceCategory.RETENTION_INTEGRITY, RiskLevel.HIGH,
                "An active retention hold refuses a hard deletion, and acquiring the same hold identity "
                        + "twice leaves exactly one hold active.",
                retentionFixtures(RETENTION_HOLD, CONTEXT_PRIMARY),
                new ReferenceRetentionRequest(GOLD_V1, true, "hold-ref-av-gold-001", true, true, false, true),
                retention("case-ref-39-hold-keeps-version-alive",
                        ReferenceOutcome.REJECTED_ACTIVE_HOLD, 1)));

        blueprints.add(retentionCase("case-ref-40-release-then-delete",
                ReferenceCategory.RETENTION_INTEGRITY, RiskLevel.HIGH,
                "After the last hold is released under explicit host policy, the hard deletion of the "
                        + "exact version succeeds and no hold remains active.",
                retentionFixtures(RETENTION_RELEASE, CONTEXT_PRIMARY),
                new ReferenceRetentionRequest(GOLD_V1, true, "hold-ref-av-gold-002", true, false, true, true),
                retention("case-ref-40-release-then-delete", ReferenceOutcome.DELETED, 0)));

        blueprints.add(retentionCase("case-ref-41-retained-by-host-policy",
                ReferenceCategory.RETENTION_INTEGRITY, RiskLevel.HIGH,
                "An unheld but referenced version is kept alive by the host retention policy, so the "
                        + "deletion is refused by policy rather than by a hold.",
                retentionFixtures(RETENTION_HOLD, CONTEXT_PRIMARY),
                new ReferenceRetentionRequest(SILVER_V1, true, null, false, false, false, true),
                retention("case-ref-41-retained-by-host-policy", ReferenceOutcome.RETAINED_BY_POLICY, 0)));

        blueprints.add(retentionCase("case-ref-42-retention-unknown-target",
                ReferenceCategory.RETENTION_INTEGRITY, RiskLevel.MEDIUM,
                "A deletion attempted against a version the store never held reports an unknown target "
                        + "rather than a successful deletion.",
                retentionFixtures(RETENTION_RELEASE, CONTEXT_PRIMARY),
                new ReferenceRetentionRequest(PLATINUM_V1, false, null, false, false, false, true),
                retention("case-ref-42-retention-unknown-target", ReferenceOutcome.UNKNOWN_TARGET, 0)));

        // --- 13. MEMBERSHIP_SET_BINDING ---------------------------------------------------------------
        blueprints.add(membershipCase("case-ref-43-membership-present",
                ReferenceCategory.MEMBERSHIP_SET_BINDING, RiskLevel.HIGH,
                "A lookup bound to one exact pin answers with that pin's member-set hash and stays "
                        + "present rather than being confused with an empty set.",
                membershipFixtures(MEMBERSHIP_MAIN, CONTEXT_PRIMARY), List.of(GOLD_V1),
                membership("case-ref-43-membership-present", ReferenceOutcome.PRESENT,
                        present(GOLD_V1, hashOf("members:av-gold-001")))));

        blueprints.add(membershipCase("case-ref-44-membership-absent",
                ReferenceCategory.MEMBERSHIP_SET_BINDING, RiskLevel.HIGH,
                "A lookup for an exact pin the fixture membership never registered is absent, which is not "
                        + "an empty member set and carries no member-set hash.",
                membershipFixtures(MEMBERSHIP_MAIN, CONTEXT_PRIMARY), List.of(BRONZE_V1),
                membership("case-ref-44-membership-absent", ReferenceOutcome.ABSENT,
                        absent(BRONZE_V1))));

        blueprints.add(membershipCase("case-ref-45-membership-pin-bound",
                ReferenceCategory.MEMBERSHIP_SET_BINDING, RiskLevel.HIGH,
                "Two versions of one audience answer with two different member-set hashes, so one identity "
                        + "never answers with another version's member set.",
                membershipFixtures(MEMBERSHIP_MAIN, CONTEXT_PRIMARY), List.of(GOLD_V1, GOLD_V2),
                membership("case-ref-45-membership-pin-bound", ReferenceOutcome.PRESENT,
                        present(GOLD_V1, hashOf("members:av-gold-001")),
                        present(GOLD_V2, hashOf("members:av-gold-002")))));

        blueprints.add(membershipCase("case-ref-46-membership-mixed-presence",
                ReferenceCategory.MEMBERSHIP_SET_BINDING, RiskLevel.MEDIUM,
                "One case looks up two pins and reaches a different answer for each, so presence is stated "
                        + "per exact pin rather than collapsed into one verdict.",
                membershipFixtures(MEMBERSHIP_MAIN, CONTEXT_PRIMARY), List.of(GOLD_V1, BRONZE_V1),
                membership("case-ref-46-membership-mixed-presence", ReferenceOutcome.PRESENT,
                        present(GOLD_V1, hashOf("members:av-gold-001")), absent(BRONZE_V1))));

        return List.copyOf(blueprints);
    }

    // ------------------------------------------------------------------ case builders

    private static CaseBlueprint documentCase(
            String caseId, ReferenceCategory category, RiskLevel riskLevel, String notes,
            ReferenceCaseFixtures fixtures, JsonNode document, List<ReferenceSiteSpec> sites,
            ReferencePin candidateRootVersion, ReferenceRecheck recheck, ReferenceCorpusExpected expected) {
        ReferenceCorpusCase caseInput = new ReferenceCorpusCase(
                evaluationVersion(), caseId, category, ReferenceScenario.REFERENCE_DOCUMENT, riskLevel,
                tags(category), notes, fixtures,
                new ReferenceCaseRequest(document, sites, candidateRootVersion, null, null, null, null, null,
                        null, recheck));
        return new CaseBlueprint(caseInput, expected);
    }

    private static CaseBlueprint resolutionCase(
            String caseId, ReferenceCategory category, RiskLevel riskLevel, String notes,
            ReferenceCaseFixtures fixtures, String utterance, List<String> candidateTexts,
            String audienceSelection, String versionSelection, ReferenceCorpusExpected expected) {
        ReferenceCorpusCase caseInput = new ReferenceCorpusCase(
                evaluationVersion(), caseId, category, ReferenceScenario.REFERENCE_RESOLUTION, riskLevel,
                tags(category), notes, fixtures,
                new ReferenceCaseRequest(null, null, null, utterance, candidateTexts, audienceSelection,
                        versionSelection, null, null, null));
        return new CaseBlueprint(caseInput, expected);
    }

    private static CaseBlueprint membershipCase(
            String caseId, ReferenceCategory category, RiskLevel riskLevel, String notes,
            ReferenceCaseFixtures fixtures, List<ReferencePin> pins, ReferenceCorpusExpected expected) {
        ReferenceCorpusCase caseInput = new ReferenceCorpusCase(
                evaluationVersion(), caseId, category, ReferenceScenario.REFERENCE_MEMBERSHIP, riskLevel,
                tags(category), notes, fixtures,
                new ReferenceCaseRequest(null, null, null, null, null, null, null, pins, null, null));
        return new CaseBlueprint(caseInput, expected);
    }

    private static CaseBlueprint retentionCase(
            String caseId, ReferenceCategory category, RiskLevel riskLevel, String notes,
            ReferenceCaseFixtures fixtures, ReferenceRetentionRequest retention,
            ReferenceCorpusExpected expected) {
        ReferenceCorpusCase caseInput = new ReferenceCorpusCase(
                evaluationVersion(), caseId, category, ReferenceScenario.REFERENCE_RETENTION, riskLevel,
                tags(category), notes, fixtures,
                new ReferenceCaseRequest(null, null, null, null, null, null, null, null, retention, null));
        return new CaseBlueprint(caseInput, expected);
    }

    // ------------------------------------------------------------------ expected builders

    private static ReferenceCorpusExpected clean(
            String caseId, JsonNode document, ReferenceExpectedGraph graph) {
        ContentHash editableDslHash = CANONICAL_JSON.hash(document);
        return new ReferenceCorpusExpected(
                evaluationVersion(), caseId, ReferenceOutcome.CLEAN, List.of(), editableDslHash,
                astHash(editableDslHash), graph, null, List.of(), null, null);
    }

    private static ReferenceCorpusExpected cleanWithRecheck(
            String caseId, JsonNode document, ReferenceExpectedGraph graph,
            ReferenceExpectedRecheck recheck) {
        ContentHash editableDslHash = CANONICAL_JSON.hash(document);
        return new ReferenceCorpusExpected(
                evaluationVersion(), caseId, ReferenceOutcome.CLEAN, List.of(), editableDslHash,
                astHash(editableDslHash), graph, null, List.of(), null, recheck);
    }

    private static ReferenceCorpusExpected rejected(String caseId, String... issueCodes) {
        return new ReferenceCorpusExpected(
                evaluationVersion(), caseId, ReferenceOutcome.REJECTED, List.of(issueCodes), null, null,
                null, null, List.of(), null, null);
    }

    private static ReferenceCorpusExpected resolved(String caseId, ReferencePin pin) {
        return new ReferenceCorpusExpected(
                evaluationVersion(), caseId, ReferenceOutcome.RESOLVED, List.of(), null, null, null,
                new ReferenceExpectedResolution(pin, resolutionEvidenceHash(pin), List.of()),
                List.of(), null, null);
    }

    private static ReferenceCorpusExpected needsInput(String caseId, String... questionCodes) {
        return new ReferenceCorpusExpected(
                evaluationVersion(), caseId, ReferenceOutcome.NEEDS_INPUT, List.of(), null, null, null,
                new ReferenceExpectedResolution(null, null, List.of(questionCodes)), List.of(), null, null);
    }

    private static ReferenceCorpusExpected unsupported(String caseId, String... questionCodes) {
        return new ReferenceCorpusExpected(
                evaluationVersion(), caseId, ReferenceOutcome.UNSUPPORTED, List.of(), null, null, null,
                new ReferenceExpectedResolution(null, null, List.of(questionCodes)), List.of(), null, null);
    }

    private static ReferenceCorpusExpected denied(String caseId, String... questionCodes) {
        return new ReferenceCorpusExpected(
                evaluationVersion(), caseId, ReferenceOutcome.DENIED, List.of(), null, null, null,
                new ReferenceExpectedResolution(null, null, List.of(questionCodes)), List.of(), null, null);
    }

    private static ReferenceCorpusExpected membership(
            String caseId, ReferenceOutcome outcome, ReferenceExpectedMembership... observations) {
        return new ReferenceCorpusExpected(
                evaluationVersion(), caseId, outcome, List.of(), null, null, null, null,
                List.of(observations), null, null);
    }

    private static ReferenceCorpusExpected retention(
            String caseId, ReferenceOutcome outcome, int activeHoldCount) {
        return new ReferenceCorpusExpected(
                evaluationVersion(), caseId, outcome, List.of(), null, null, null, null, List.of(),
                new ReferenceExpectedRetention(outcome, activeHoldCount), null);
    }

    private static ReferenceExpectedMembership present(ReferencePin pin, ContentHash memberSetHash) {
        return new ReferenceExpectedMembership(pin, ReferenceOutcome.PRESENT, memberSetHash);
    }

    private static ReferenceExpectedMembership absent(ReferencePin pin) {
        return new ReferenceExpectedMembership(pin, ReferenceOutcome.ABSENT, null);
    }

    // ------------------------------------------------------------------ pending review ledger

    /** The honest review ledger of a case awaiting review: one pending entry per owner role. */
    static List<CaseReviewEntry> pendingReviews() {
        List<CaseReviewEntry> entries = new ArrayList<>();
        for (ReviewRole role : ReviewRole.ordered()) {
            entries.add(CaseReviewEntry.pending(role,
                    "Pending " + role + " owner review of this exact case and expected revision."));
        }
        return List.copyOf(entries);
    }

    // ------------------------------------------------------------------ fixture slot helpers

    private static ReferenceCaseFixtures documentFixtures(
            String catalog, String dataScope, String programPolicy, String referencePolicy, String context) {
        return new ReferenceCaseFixtures(METADATA_MAIN, catalog, dataScope, programPolicy, referencePolicy,
                null, null, null, context);
    }

    private static ReferenceCaseFixtures onlyContextFixtures(String context) {
        return new ReferenceCaseFixtures(null, null, null, null, null, null, null, null, context);
    }

    private static ReferenceCaseFixtures directoryFixtures(String directory, String context) {
        return new ReferenceCaseFixtures(null, null, null, null, null, directory, null, null, context);
    }

    private static ReferenceCaseFixtures membershipFixtures(String membership, String context) {
        return new ReferenceCaseFixtures(null, null, null, null, null, null, membership, null, context);
    }

    private static ReferenceCaseFixtures retentionFixtures(String retentionPolicy, String context) {
        return new ReferenceCaseFixtures(null, null, null, null, null, null, null, retentionPolicy, context);
    }

    private static List<String> tags(ReferenceCategory category) {
        return List.of("reference-corpus", category.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
    }

    // ------------------------------------------------------------------ payload helpers

    private static Object catalog(String catalogId, String tenantId, List<Object> versions) {
        return map("catalogId", catalogId, "tenantId", tenantId, "versions", versions);
    }

    private static Object version(
            ReferencePin pin, String programId, String rootSubject, int expandedComplexity,
            List<ReferencePin> dependencies) {
        List<Object> declared = new ArrayList<>();
        for (ReferencePin dependency : dependencies) {
            declared.add(map("audienceId", dependency.audienceId(), "versionId", dependency.versionId(),
                    "versionHash", dependency.versionHash().value()));
        }
        return map("audienceId", pin.audienceId(), "versionId", pin.versionId(),
                "versionHash", pin.versionHash().value(), "availability", "PUBLISHED", "immutable", true,
                "programId", programId, "rootSubject", rootSubject, "expandedComplexity", expandedComplexity,
                "dependencies", declared);
    }

    private static Object directory(String directoryId, List<Object> audiences) {
        return map("directoryId", directoryId, "audiences", audiences);
    }

    private static Object audience(
            String audienceId, String displayLabel, boolean accessible, List<ReferencePin> publishedVersions) {
        List<Object> published = new ArrayList<>();
        for (ReferencePin version : publishedVersions) {
            published.add(map("versionId", version.versionId(), "versionHash", version.versionHash().value()));
        }
        return map("audienceId", audienceId, "displayLabel", displayLabel, "accessible", accessible,
                "publishedVersions", published);
    }

    private static Object membershipEntry(ReferencePin pin, int memberCount, ContentHash memberSetHash) {
        return map("audienceId", pin.audienceId(), "versionId", pin.versionId(),
                "versionHash", pin.versionHash().value(), "memberCount", memberCount,
                "memberSetHash", memberSetHash.value());
    }

    private static Object context(String tenantId, String programId, String dataScopeId) {
        return map("tenantId", tenantId, "programId", programId, "userId", "user-ref-001",
                "dataScopeId", dataScopeId, "metadataSnapshotId", "metadata-ref-001", "locale", "en-US",
                "timezone", "Asia/Shanghai", "reportingCurrency", "CNY", "asOf", "2026-10-01T02:00:00Z",
                "dataWatermark", "2026-10-01T01:55:00Z");
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            map.put((String) keyValues[index], keyValues[index + 1]);
        }
        return map;
    }

    // ------------------------------------------------------------------ document helpers

    private static JsonNode document(String rootNodeId, String root) {
        return CANONICAL_JSON.readTree("{\"dslVersion\":\"segment-dsl/1.0\",\"rootNodeId\":\"" + rootNodeId
                + "\",\"subject\":\"MEMBER\",\"root\":" + root + "}");
    }

    private static String group(String nodeId, String op, String... children) {
        return "{\"nodeId\":\"" + nodeId + "\",\"type\":\"GROUP\",\"op\":\"" + op + "\",\"children\":["
                + String.join(",", children) + "]}";
    }

    private static String not(String nodeId, String child) {
        return "{\"nodeId\":\"" + nodeId + "\",\"type\":\"NOT\",\"children\":[" + child + "]}";
    }

    private static String exists(String nodeId, String relation, String child) {
        return "{\"nodeId\":\"" + nodeId + "\",\"type\":\"EXISTS\",\"relation\":\"" + relation
                + "\",\"children\":[" + child + "]}";
    }

    private static String reference(String nodeId, ReferencePin pin) {
        return "{\"nodeId\":\"" + nodeId + "\",\"type\":\"REFERENCE\",\"audienceId\":\"" + pin.audienceId()
                + "\",\"versionId\":\"" + pin.versionId() + "\",\"versionHash\":\""
                + pin.versionHash().value() + "\"}";
    }

    private static ReferenceSiteSpec site(String nodeId, ReferencePin pin, SubjectKind scopeSubject) {
        return new ReferenceSiteSpec(nodeId, pin, scopeSubject);
    }

    // ------------------------------------------------------------------ hashes

    private static ReferenceExpectedGraph graph(
            List<ReferencePin> versions, int maxDepth, int expandedComplexity) {
        List<ReferencePin> ordered = new ArrayList<>(versions);
        ordered.sort(Comparator.comparing(ReferencePin::identityKey));
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("graphVersion", "segment-audience-reference-graph/1.0");
        material.put("versions", ordered);
        material.put("distinctVersions", ordered.size());
        material.put("maxDepth", maxDepth);
        material.put("expandedComplexity", expandedComplexity);
        return new ReferenceExpectedGraph(ordered, ordered.size(), maxDepth, expandedComplexity,
                CANONICAL_JSON.hash(material));
    }

    private static ContentHash astHash(ContentHash editableDslHash) {
        return CANONICAL_JSON.hash(map("astVersion", "segment-ast/1.0",
                "editableDslHash", editableDslHash.value()));
    }

    private static ContentHash resolutionEvidenceHash(ReferencePin pin) {
        return hashOf("resolution-evidence:" + pin.toVersionRef().pinKey());
    }

    static ContentHash hashOf(String seed) {
        return ContentHash.sha256(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static ReferencePin pin(String audienceId, String versionId) {
        return new ReferencePin(audienceId, versionId, hashOf(audienceId + "/" + versionId));
    }

    private static com.company.campaign.contracts.SchemaVersion evaluationVersion() {
        return com.company.campaign.evaluation.segment.contract.EvaluationVersions.CURRENT;
    }

    /** The repository-relative path of one case asset, used by the generator and the tests. */
    static String casePath(String caseId) {
        return ROOT + "/cases/" + caseId + ".json";
    }

    /** The repository-relative path of one expected-result asset. */
    static String expectedPath(String caseId) {
        return ROOT + "/expected/" + caseId + ".json";
    }

    /** The repository-relative path of one review-ledger asset. */
    static String reviewPath(String caseId) {
        return ROOT + "/reviews/" + caseId + ".json";
    }
}
