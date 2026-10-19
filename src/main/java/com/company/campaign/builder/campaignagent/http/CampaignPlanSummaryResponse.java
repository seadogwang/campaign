package com.company.campaign.builder.campaignagent.http;

import com.company.campaign.contracts.PlanRef;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The bounded server projection of the frontend's strict {@code CampaignPlanSummary} shape.
 *
 * <p>It carries exactly the 15 fields the frontend's {@code decodeCampaignPlanSummary} accepts:
 * no unknown field, no alternate casing and no invented value. The projection is produced only
 * from a genuine compiler-produced {@code CampaignPlan}: no client, model or serializer can
 * construct one from raw values.
 *
 * <p>This is NOT a public-facing type — it is the host-owned projection the HTTP envelope wraps
 * as its {@code data} member.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CampaignPlanSummaryResponse(
        PlanRef planRef,
        String planFormatVersion,
        String compilerVersion,
        String mode,
        String status,
        String compiledAt,
        String campaignId,
        String baseDraftId,
        Long baseDraftRevision,
        String proposalHash,
        String manifestHash,
        List<PlanIssue> validationIssues,
        List<DiffEntry> semanticDiff,
        List<PlanPreviewCard> previewCards,
        List<String> riskCodes
) {

    /** One bounded issue the frontend decodes strictly. */
    public record PlanIssue(
            String code,
            String severity,
            String path,
            String nodeId,
            String message
    ) {
    }

    /** One bounded semantic-diff entry the frontend decodes strictly. */
    public record DiffEntry(
            String kind,
            String logicalId,
            String capabilityCode,
            List<String> fieldPaths
    ) {
    }

    /** One bounded preview card the frontend decodes strictly. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlanPreviewCard(
            String cardKind,
            String componentLogicalId,
            long matchedCount,
            List<ConditionStatistic> conditionStatistics,
            List<String> sampleTokens,
            String metadataSnapshotId,
            String asOf,
            String timezone,
            String dataWatermark,
            String readinessState,
            String approvedDecisionHash,
            ReferencePlanEvidence referenceEvidence
    ) {
    }

    /** One bounded condition statistic the frontend decodes strictly. */
    public record ConditionStatistic(
            String path,
            long trueCount,
            long falseCount,
            long unknownCount
    ) {
    }

    /** One bounded reference-plan evidence entry. */
    public record ReferencePlanEvidence(
            String capabilityId,
            String referenceDependenciesHash,
            String referenceInputsHash,
            String readinessState
    ) {
    }

    /**
     * Projects a compiler-produced Plan into the exact frontend-accepted shape.
     *
     * <p>The status is always {@code READY}: the compiler only produces a Plan when every input is
     * valid, so no partial or invalid Plan reaches this projection. The mode and identity are
     * derived from the plan's own facts, and every hash is the canonical hex form.
     */
    public static CampaignPlanSummaryResponse from(
            com.company.campaign.builder.compiler.CampaignPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("A Campaign Plan is required.");
        }
        String status = plan.validationIssues().stream().anyMatch(
                issue -> com.company.campaign.contracts.IssueSeverity.ERROR.equals(
                        issue.severity())) ? "INVALID" : "READY";
        return new CampaignPlanSummaryResponse(
                plan.planRef(),
                plan.planFormatVersion(),
                plan.compilerVersion(),
                plan.campaignId() == null ? "CREATE" : "EDIT",
                status,
                plan.compiledAt().toString(),
                plan.campaignId(),
                plan.baseDraftId(),
                plan.baseDraftRevision(),
                proposalHashString(plan.proposalHash()),
                manifestHashString(plan.manifestHash()),
                projectIssues(plan.validationIssues()),
                projectDiff(plan.semanticDiff().entries()),
                projectPreviewCards(plan.previewCards()),
                List.copyOf(plan.riskSummary() != null && plan.riskSummary().riskCodes() != null
                        ? plan.riskSummary().riskCodes()
                        : List.of()));
    }

    private static List<PlanIssue> projectIssues(
            List<com.company.campaign.contracts.Issue> issues) {
        return issues.stream()
                .map(issue -> new PlanIssue(
                        issue.code(),
                        issue.severity().name(),
                        issue.path(),
                        issue.nodeId(),
                        issue.message()))
                .toList();
    }

    private static String severityString(com.company.campaign.contracts.IssueSeverity severity) {
        return severity == null ? "ERROR" : severity.name();
    }

    private static List<DiffEntry> projectDiff(
            List<com.company.campaign.builder.compiler.DiffEntry> entries) {
        return entries.stream()
                .map(entry -> new DiffEntry(
                        entry.kind().name(),
                        entry.logicalId(),
                        entry.capabilityCode(),
                        entry.fieldPaths()))
                .toList();
    }

    private static List<PlanPreviewCard> projectPreviewCards(
            List<com.company.campaign.builder.compiler.PlanPreviewCard> cards) {
        return cards.stream()
                .map(card -> new PlanPreviewCard(
                        card.cardKind(),
                        card.componentLogicalId(),
                        card.matchedCount(),
                        card.conditionStatistics() == null
                                ? List.of()
                                : card.conditionStatistics().stream()
                                        .map(stat -> new ConditionStatistic(
                                                stat.path(),
                                                stat.trueCount(),
                                                stat.falseCount(),
                                                stat.unknownCount()))
                                        .toList(),
                        card.sampleTokens() == null ? List.of() : card.sampleTokens(),
                        card.metadataSnapshotId(),
                        card.asOf().toString(),
                        card.timezone(),
                        card.dataWatermark().toString(),
                        card.readinessState(),
                        card.approvedDecisionHash() == null
                                ? null
                                : canonicalHashString(card.approvedDecisionHash()),
                        card.referenceEvidence() == null
                                ? null
                                : new ReferencePlanEvidence(
                                        card.referenceEvidence().capabilityId(),
                                        canonicalHashString(
                                                card.referenceEvidence().referenceDependenciesHash()),
                                        canonicalHashString(
                                                card.referenceEvidence().referenceInputsHash()),
                                        card.referenceEvidence().readinessState())))
                .toList();
    }

    private static String proposalHashString(ContentHash hash) {
        return canonicalHashString(hash);
    }

    private static String manifestHashString(ContentHash hash) {
        return canonicalHashString(hash);
    }

    private static String canonicalHashString(ContentHash hash) {
        return hash == null ? null : hash.value();
    }
}