package com.company.campaign.evaluation.segment.runner.testsupport;

import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.evaluation.segment.runner.DecodingParameters;
import com.company.campaign.evaluation.segment.runner.EvaluationRunContract;
import com.company.campaign.evaluation.segment.runner.EvaluationRunRequest;

import java.nio.charset.StandardCharsets;

/**
 * The synthetic T13 assets shared by the focused tests: a real fixture payload the genuine T09 loader accepts,
 * its declared member universe, and the explicit run request a test starts a run with.
 */
public final class T13TestAssets {

    /** The declared universe of {@link #membersPayload()}. */
    public static final MemberIdSet TIER_UNIVERSE = MemberIdSet.of("m-001", "m-002", "m-003");

    private T13TestAssets() {
    }

    /** A real T09 fixture payload: three members, one order and one event. */
    public static String membersPayload() {
        return "{\"asOf\":\"2026-10-01T02:00:00Z\",\"watermark\":\"2026-10-01T01:55:00Z\","
                + "\"dataset\":\"fixture_t13_members\",\"fixtureId\":\"fixture-t13-members\",\"role\":\"PRIMARY\","
                + "\"currencies\":[\"CNY\"],\"statuses\":[\"ACTIVE\",\"DORMANT\"],"
                + "\"members\":["
                + "{\"memberId\":\"m-001\",\"tier\":\"GOLD\",\"status\":\"ACTIVE\",\"city\":\"City-1\","
                + "\"joinDate\":\"2026-01-15\"},"
                + "{\"memberId\":\"m-002\",\"tier\":\"SILVER\",\"status\":\"ACTIVE\",\"city\":\"City-2\","
                + "\"joinDate\":\"2026-02-15\"},"
                + "{\"memberId\":\"m-003\",\"tier\":\"GOLD\",\"status\":\"DORMANT\",\"city\":\"City-3\","
                + "\"joinDate\":\"2026-03-15\"}],"
                + "\"orders\":[{\"orderId\":\"o-001\",\"memberId\":\"m-001\",\"amount\":\"120.00\","
                + "\"currency\":\"CNY\",\"paidAt\":\"2026-09-30T09:00:00Z\",\"refunded\":\"0.00\","
                + "\"status\":\"PAID\"}],"
                + "\"events\":[{\"eventId\":\"e-001\",\"memberId\":\"m-001\","
                + "\"occurredAt\":\"2026-09-30T10:00:00Z\",\"type\":\"LOGIN\"}],"
                + "\"refunds\":[]}";
    }

    /** The exact bytes of {@link #membersPayload()}, for payload-hash assertions. */
    public static byte[] membersPayloadBytes() {
        return membersPayload().getBytes(StandardCharsets.UTF_8);
    }

    /** The one explicit run request shape every focused test uses unless it is deliberately varying a pin. */
    public static EvaluationRunRequest request(String reportId) {
        return new EvaluationRunRequest(
                reportId,
                modelPin(),
                DecodingParameters.deterministicReplay(4096, 20261006L),
                "code-revision-t13-test",
                "environment-t13-test",
                T13TestCorpus.MANIFEST_REFERENCE);
    }

    /** The exact frozen provider/model/version pin of a deterministic replay run. */
    public static EvaluationRunContract.ModelPin modelPin() {
        return new EvaluationRunContract.ModelPin(
                "deterministic-replay-provider", "t13-replay-model", "t13-replay-model/1.0");
    }
}