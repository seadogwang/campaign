package com.company.campaign.preview.adapter;

import com.company.campaign.preview.PreviewBackendException;
import com.company.campaign.preview.PreviewBackendResult;
import com.company.campaign.preview.testsupport.PreviewTestSupport;
import com.company.campaign.query.fixture.FixtureDataset;
import com.company.campaign.query.fixture.FixtureScopeBinding;
import com.company.campaign.query.plan.ReferenceExecutionCheck;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.testsupport.ReferenceQueryFixtures;
import com.company.campaign.segment.reference.AudienceMembershipPort;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.segment.reference.AudienceVersionAvailability;
import com.company.campaign.segment.reference.InMemoryAudienceVersionCatalog;
import com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.UNIVERSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T18: a preview over a reference-bearing plan runs the execution guard first and resolves membership by the
 * exact pin, and a preview wired without that evidence refuses the plan outright.
 *
 * <p>Preview generation must not be able to weaken or remove the re-check, so the accepted reference-free
 * adapter refuses a reference plan rather than previewing something it cannot prove.
 */
class FixturePreviewBackendReferenceTest {

    @TempDir
    Path tempDir;

    private FixtureDataset dataset;
    private InMemoryAudienceVersionCatalog catalog;

    @BeforeEach
    void setUp() {
        dataset = PreviewTestSupport.dataset(tempDir);
        catalog = ReferenceQueryFixtures.catalogOf(ReferenceQueryFixtures.version());
    }

    private FixtureScopeBinding scopeBinding() {
        return ReferenceQueryFixtures.referenceScope(UNIVERSE, UNIVERSE);
    }

    private SemanticQueryPlan plan(AudienceVersionRef version) {
        return ReferenceQueryFixtures.plan(ReferenceQueryFixtures.referenceDocument(version), catalog);
    }

    private FixturePreviewExecutionBackend backend(
            AudienceMembershipPort membership, ReferenceExecutionCheck referenceExecutionCheck) {
        return new FixturePreviewExecutionBackend(
                dataset,
                com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardMapping(),
                scopeBinding(),
                null,
                PreviewTestSupport.BACKEND_VERSION,
                membership,
                referenceExecutionCheck);
    }

    @Test
    void aReferencePreviewResolvesMembershipByTheExactPin() {
        AudienceVersionRef version = ReferenceQueryFixtures.version();
        ReferenceExecutionCheck check =
                ReferenceQueryFixtures.executionCheck(catalog, SegmentReferenceFixtures.allowAll());

        PreviewBackendResult result = backend(
                ReferenceQueryFixtures.members(version, Set.of("m-002", "m-006")), check)
                .execute(plan(version), SegmentValidationFixtures.context());

        assertThat(result.memberIdSet().memberIds()).containsExactly("m-002", "m-006");
        assertThat(result.matchedCount()).isEqualTo(2);
        assertThat(result.planHash()).isEqualTo(plan(version).planHash());
        assertThat(result.dataScopeId()).isEqualTo(plan(version).dataScopePredicate().dataScopeId());
    }

    @Test
    void theAcceptedReferenceFreeAdapterRefusesAReferencePlan() {
        AudienceVersionRef version = ReferenceQueryFixtures.version();
        FixturePreviewExecutionBackend referenceFree = new FixturePreviewExecutionBackend(
                dataset,
                com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardMapping(),
                scopeBinding(),
                null,
                PreviewTestSupport.BACKEND_VERSION);

        assertThatThrownBy(() -> referenceFree.execute(plan(version), SegmentValidationFixtures.context()))
                .isInstanceOf(PreviewBackendException.class);
    }

    @Test
    void previewRefusesADriftedReferenceBeforeEvaluatingAnyMember() {
        AudienceVersionRef version = ReferenceQueryFixtures.version();
        ReferenceExecutionCheck check =
                ReferenceQueryFixtures.executionCheck(catalog, SegmentReferenceFixtures.allowAll());
        FixturePreviewExecutionBackend referenceBackend = backend(
                ReferenceQueryFixtures.members(version, Set.of("m-002")), check);
        SemanticQueryPlan plan = plan(version);

        catalog.updateAvailability(version, AudienceVersionAvailability.DELETED);

        assertThatThrownBy(() -> referenceBackend.execute(plan, SegmentValidationFixtures.context()))
                .isInstanceOf(PreviewBackendException.class);
    }

    @Test
    void aReferencePreviewWithoutALocalMembershipDatasetRefuses() {
        AudienceVersionRef version = ReferenceQueryFixtures.version();
        ReferenceExecutionCheck check =
                ReferenceQueryFixtures.executionCheck(catalog, SegmentReferenceFixtures.allowAll());

        assertThatThrownBy(() -> backend(ReferenceQueryFixtures.noMembers(), check)
                .execute(plan(version), SegmentValidationFixtures.context()))
                .isInstanceOf(PreviewBackendException.class);
    }

    @Test
    void aReferencePreviewIsDeterministic() {
        AudienceVersionRef version = ReferenceQueryFixtures.version();
        ReferenceExecutionCheck check =
                ReferenceQueryFixtures.executionCheck(catalog, SegmentReferenceFixtures.allowAll());
        FixturePreviewExecutionBackend referenceBackend = backend(
                ReferenceQueryFixtures.members(version, Set.of("m-001", "m-004")), check);
        SemanticQueryPlan plan = plan(version);

        PreviewBackendResult first = referenceBackend.execute(plan, SegmentValidationFixtures.context());
        PreviewBackendResult second = referenceBackend.execute(plan, SegmentValidationFixtures.context());

        assertThat(second.resultHash()).isEqualTo(first.resultHash());
        assertThat(second.memberIdSet().memberIds()).containsExactlyElementsOf(
                first.memberIdSet().memberIds());
        assertThat(List.copyOf(first.conditionStatistics())).isNotEmpty();
    }
}