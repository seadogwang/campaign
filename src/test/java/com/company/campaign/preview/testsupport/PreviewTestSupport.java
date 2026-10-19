package com.company.campaign.preview.testsupport;

import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.repository.InMemoryMetadataSnapshotRepository;
import com.company.campaign.metadata.repository.MetadataSnapshotRepository;
import com.company.campaign.preview.PreviewPolicy;
import com.company.campaign.preview.PreviewSampleHasher;
import com.company.campaign.preview.SegmentPreviewService;
import com.company.campaign.preview.adapter.FixturePreviewExecutionBackend;
import com.company.campaign.query.fixture.FixtureDataset;
import com.company.campaign.query.fixture.testsupport.FixtureTestSupport;
import com.company.campaign.query.plan.SegmentQueryPlanner;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.validation.SegmentValidationPolicy;
import com.company.campaign.segment.validation.SegmentValidator;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.context.TrustedContextProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures.attribute;
import static com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures.any;
import static com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures.booleanValue;
import static com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures.tierEquals;
import static com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures.value;

/**
 * Deterministic T09C test fixtures built only from accepted public contracts.
 *
 * <p>No clock, network, database, credential, random identifier or T06 corpus asset is touched. Fixture
 * payloads are written beneath a JUnit temporary directory and loaded through the production T09A loader, so
 * containment, exact-byte hashing and strict parsing are exercised by every Preview test.
 */
public final class PreviewTestSupport {

    /** A 256-bit test HMAC key (hex text, UTF-8 encoded to exactly 32 bytes). */
    public static final String KEY_A = "0123456789abcdef0123456789abcdef";

    /** A different 256-bit test HMAC key. */
    public static final String KEY_B = "fedcba9876543210fedcba9876543210";

    /** The exact version the fixture backend identity reports unless a test names another one. */
    public static final String BACKEND_VERSION = "fixture-backend-v1";

    private PreviewTestSupport() {
    }

    // ------------------------------------------------------------------ identity

    public static MetadataSnapshot snapshot() {
        return SegmentValidationFixtures.snapshot();
    }

    public static MetadataSnapshotRepository repository() {
        return repository(snapshot());
    }

    public static MetadataSnapshotRepository repository(MetadataSnapshot snapshot) {
        InMemoryMetadataSnapshotRepository repository = new InMemoryMetadataSnapshotRepository();
        if (snapshot != null) {
            repository.publish(snapshot);
        }
        return repository;
    }

    public static PreviewSampleHasher hasher(String hexKey) {
        return new PreviewSampleHasher(hexKey.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * An explicit test-only policy, built directly and bounded far below every absolute limit.
     *
     * <p>No production default exists to call: a test must always name every bound it relies on.
     */
    public static PreviewPolicy policy() {
        return new PreviewPolicy(262_144, 128, 100, 1_000_000, Duration.ofHours(24));
    }

    // ------------------------------------------------------------------ datasets and backends

    public static FixtureDataset dataset(Path evaluationRoot) {
        return FixtureTestSupport.load(evaluationRoot, FixtureTestSupport.standardPayload());
    }

    public static FixturePreviewExecutionBackend fixtureBackend(FixtureDataset dataset) {
        return new FixturePreviewExecutionBackend(
                dataset, FixtureTestSupport.standardMapping(), FixtureTestSupport.standardScope(),
                null, BACKEND_VERSION);
    }

    // ------------------------------------------------------------------ service

    public static SegmentPreviewService service(
            TrustedContextProvider provider,
            MetadataSnapshotRepository repository,
            com.company.campaign.preview.PreviewExecutionBackend backend,
            AuthorizationService authorizationService,
            PreviewPolicy policy,
            PreviewSampleHasher hasher) {
        return new SegmentPreviewService(
                provider,
                repository,
                new SegmentDslCodec(),
                new SegmentValidator(authorizationService),
                SegmentValidationPolicy.standard(),
                new SegmentQueryPlanner(authorizationService),
                backend,
                authorizationService,
                policy,
                hasher);
    }

    /** The fully defaulted harness: allow-all authorization, standard policy and a 256-bit test key. */
    public static SegmentPreviewService service(
            TrustedContextProvider provider,
            MetadataSnapshotRepository repository,
            com.company.campaign.preview.PreviewExecutionBackend backend) {
        return service(
                provider, repository, backend, SegmentValidationFixtures.allowAll(),
                policy(), hasher(KEY_A));
    }

    // ------------------------------------------------------------------ documents

    /** A document whose only predicate matches the three GOLD members of the standard dataset. */
    public static SegmentDsl goldDocument() {
        return SegmentValidationFixtures.publish(tierEquals("n-tier", "GOLD"));
    }

    /** A document with four structural paths, for deterministic ordering and server-cap tests. */
    public static SegmentDsl multiPathDocument() {
        Predicate predicate = any("n-root", List.of(
                tierEquals("n-gold", "GOLD"),
                tierEquals("n-silver", "SILVER"),
                attribute("n-flag", "member.flag",
                        com.company.campaign.metadata.domain.ComparisonOperator.EQUAL,
                        value(booleanValue(true)))));
        return SegmentValidationFixtures.publish(predicate);
    }

    /** A document that references an unknown attribute definition, so genuine T07 validation rejects it. */
    public static SegmentDsl unknownDefinitionDocument() {
        return SegmentValidationFixtures.publish(
                attribute("n-unknown", "member.does_not_exist",
                        com.company.campaign.metadata.domain.ComparisonOperator.EQUAL,
                        value(booleanValue(true))));
    }

    // ------------------------------------------------------------------ request bodies

    /** The exact strict request body for a document and its presentation flags. */
    public static byte[] body(SegmentDsl document, boolean includeSample, boolean includeConditionStats) {
        String dsl = new SegmentDslCodec().writeString(document);
        String json = "{\"segmentDsl\":" + dsl
                + ",\"presentation\":{\"includeSample\":" + includeSample
                + ",\"includeConditionStats\":" + includeConditionStats + "}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** The standard success body with a sample requested and statistics enabled. */
    public static byte[] successBody(SegmentDsl document) {
        return body(document, true, true);
    }

    public static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** A policy with a small freshness bound, for stale-data tests. */
    public static PreviewPolicy policyWithMaxDataAge(Duration maxDataAge) {
        PreviewPolicy standard = policy();
        return new PreviewPolicy(
                standard.maxRequestBytes(), standard.maxConditionStats(), standard.maxSampleSize(),
                standard.maxMatchedCount(), maxDataAge);
    }

    /** A 32-byte key derived from a single repeated byte, for tests that must never log the key. */
    public static byte[] keyBytes(byte value) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, value);
        return key;
    }
}