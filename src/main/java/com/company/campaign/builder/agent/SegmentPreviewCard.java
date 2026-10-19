package com.company.campaign.builder.agent;

import com.company.campaign.preview.PreviewConditionStatistic;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The bounded, safe presentation card derived only from the genuine T09C Preview response.
 *
 * <p>It carries only already-published safe facts: the matched count, the watermark/freshness context, the
 * approved condition statistics and — only when separately authorized — hashed sample tokens. It pins the
 * Preview, plan, DSL, canonical, Metadata and Skill hashes so a caller can detect a stale presentation.
 *
 * <p>It never carries a raw member id or member set, a tenant/program/user/DataScope identifier, a permission,
 * a Metadata label, a DSL operand, SQL text, a fixture fact or an internal exception message. The default is no
 * sample at all: {@link #sampleTokens()} is null and the sample is genuinely absent rather than empty.
 */
public record SegmentPreviewCard(
        String status,
        int matchedCount,
        String metadataSnapshotId,
        String asOf,
        String timezone,
        String dataWatermark,
        List<PreviewConditionStatistic> conditionStatistics,
        List<String> sampleTokens,
        ContentHash previewHash,
        ContentHash planHash,
        ContentHash dslHash,
        ContentHash canonicalAstHash,
        ContentHash metadataHash,
        ContentHash skillHash
) {

    private static final Pattern SAMPLE_TOKEN = Pattern.compile("^sha256:[0-9a-f]{64}$");

    public SegmentPreviewCard {
        if (status == null || metadataSnapshotId == null || asOf == null || timezone == null || dataWatermark == null
                || previewHash == null || planHash == null || dslHash == null || canonicalAstHash == null
                || metadataHash == null || skillHash == null) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "A Preview card requires its safe facts and pins");
        }
        if (matchedCount < 0) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "A Preview card matched count must not be negative");
        }
        conditionStatistics = conditionStatistics == null
                ? null
                : List.copyOf(new ArrayList<>(conditionStatistics));
        if (conditionStatistics != null && conditionStatistics.size() > SegmentAgentLimits.MAX_CARD_CONDITION_STATS) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.REQUEST_INVALID, "A Preview card exceeds its condition-statistic bound");
        }
        sampleTokens = sampleTokens == null ? null : List.copyOf(new ArrayList<>(sampleTokens));
        if (sampleTokens != null) {
            if (sampleTokens.size() > SegmentAgentLimits.MAX_CARD_SAMPLE_TOKENS) {
                throw SegmentAgentException.invalid(
                        SegmentAgentErrorCodes.REQUEST_INVALID, "A Preview card exceeds its sample bound");
            }
            for (String token : sampleTokens) {
                if (token == null || !SAMPLE_TOKEN.matcher(token).matches()) {
                    throw SegmentAgentException.invalid(
                            SegmentAgentErrorCodes.REQUEST_INVALID,
                            "A Preview card sample token must be an opaque hash, never a raw identifier");
                }
            }
        }
    }

    /** True when an authorized, already-hashed sample is present on the card. */
    public boolean hasSample() {
        return sampleTokens != null;
    }
}