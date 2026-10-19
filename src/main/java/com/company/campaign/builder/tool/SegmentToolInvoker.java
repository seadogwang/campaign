package com.company.campaign.builder.tool;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.Issue;
import com.company.campaign.contracts.ToolEnvelope;
import com.company.campaign.contracts.ToolFailure;
import com.company.campaign.contracts.ToolInput;
import com.company.campaign.contracts.ToolResultEnvelope;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The host-owned closed Tool invoker of one Agent run.
 *
 * <p>The invoker is the single control point of a protected stage: it decides whether a call is admitted
 * <em>before</em> the Resolver, Validator, Planner, Preview or explanation implementation is entered, and it
 * hands the accepted typed result back through {@link SegmentToolAdmission}. A caller cannot register a Tool,
 * choose an implementation, override the Skill allowlist or supply trusted context: the invoker resolves the
 * exact Skill catalog and host run evidence itself, and every handler reads only host-owned evidence.
 *
 * <p>Every attempt is judged against the per-run call count, the canonical payload size, the Skill's declared
 * Tool order, at-most-once Tool use and the terminal policy, and every attempt — admitted or refused — appends
 * exactly one audit-safe {@link SegmentToolCallRecord} carrying the redacted canonical payload fingerprint
 * before returning. Unknown, disallowed, stale, duplicate, out-of-order, over-limit and post-terminal calls
 * therefore fail closed before any Tool implementation runs, and still leave audit evidence. A rank may be
 * skipped only in the sense that {@code lastRank} must strictly increase: this invoker never requires the next
 * rank to be exactly {@code lastRank + 1}. The fixed T11 Agent flow issues the consecutive calls its Skill
 * declares, so a skipped phase is a host orchestration choice, never a model one.
 *
 * <p>There is no generic reflection, script, URL, repository, JDBC or arbitrary-JSON callback Tool, and
 * Save/Apply/Publish/Pause/Resume/Retry/Archive are host-only and cannot be named here.
 *
 * <p>One invoker belongs to exactly one run: it holds the run's call bookkeeping that must never be shared or
 * carried across runs.
 */
public final class SegmentToolInvoker {

    /** The absolute bound on the canonical bytes of one Tool outcome. */
    public static final int MAX_OUTCOME_BYTES = 65_536;

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final SegmentToolCatalog catalog;
    private final Map<String, SegmentToolHandler> handlers;
    private final SegmentToolRunContext run;
    private final SegmentToolLimitsSnapshot limits;

    private final Set<String> usedCallIds = new LinkedHashSet<>();
    private final Set<String> usedToolIds = new LinkedHashSet<>();
    private final List<SegmentToolCallRecord> trace = new ArrayList<>();
    private int lastRank = -1;
    private int callCount;
    private boolean terminal;

    public SegmentToolInvoker(
            SegmentToolCatalog catalog,
            Map<String, SegmentToolHandler> handlers,
            SegmentToolRunContext run) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.handlers = handlers == null ? Map.of() : Map.copyOf(handlers);
        this.run = Objects.requireNonNull(run, "run");
        this.limits = new SegmentToolLimitsSnapshot(catalog.maxToolCalls(), catalog.maxToolPayloadBytes());
    }

    /** Invokes one host-orchestrated Tool call and returns its bound admission. */
    public SegmentToolAdmission invoke(ToolEnvelope envelope) {
        requireEnvelope(envelope);
        String callId = envelope.toolCallId();
        String toolId = envelope.toolId();
        ToolInput payload = envelope.payload();
        ContentHash inputHash = payload == null ? CANONICAL_JSON.hash(Map.of()) : CANONICAL_JSON.hash(payload.arguments());

        if (terminal) {
            return refuse(callId, toolId, SegmentToolCallRecord.UNKNOWN_RANK, Disposition.INVALID,
                    SegmentToolErrorCodes.TOOL_POST_TERMINAL, inputHash);
        }
        if (!ContractVersions.isCurrent(envelope.schemaVersion())) {
            return refuse(callId, toolId, SegmentToolCallRecord.UNKNOWN_RANK, Disposition.INVALID,
                    SegmentToolErrorCodes.TOOL_SCHEMA_STALE, inputHash);
        }
        if (payload == null) {
            return refuse(callId, toolId, SegmentToolCallRecord.UNKNOWN_RANK, Disposition.INVALID,
                    SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT, inputHash);
        }
        if (SegmentToolIds.attemptsForbiddenCapability(toolId)) {
            return refuse(callId, toolId, SegmentToolCallRecord.UNKNOWN_RANK, Disposition.DENIED,
                    SegmentToolErrorCodes.TOOL_FORBIDDEN_CAPABILITY, inputHash);
        }
        SegmentToolDescriptor descriptor = catalog.descriptor(toolId);
        if (descriptor == null) {
            String code = SegmentToolIds.isKnown(toolId)
                    ? SegmentToolErrorCodes.TOOL_NOT_ALLOWED
                    : SegmentToolErrorCodes.TOOL_UNKNOWN;
            return refuse(callId, toolId, SegmentToolCallRecord.UNKNOWN_RANK, Disposition.INVALID, code, inputHash);
        }
        if (usedCallIds.contains(callId) || usedToolIds.contains(toolId)) {
            return refuse(callId, toolId, descriptor.rank(), Disposition.INVALID,
                    SegmentToolErrorCodes.TOOL_DUPLICATE_CALL, inputHash);
        }
        if (callCount >= limits.maxToolCalls()) {
            return refuse(callId, toolId, descriptor.rank(), Disposition.INVALID,
                    SegmentToolErrorCodes.TOOL_CALL_LIMIT_EXCEEDED, inputHash);
        }
        if (descriptor.rank() <= lastRank
                || (callCount == 0 && descriptor.rank() != catalog.ordered().get(0).rank())) {
            // The first admitted call must be the Skill's first permitted Tool, and every later call must
            // strictly advance the rank, so a repeated or reversed phase is refused deterministically. A
            // non-consecutive rank is accepted: only the host orchestrator decides which declared phases run.
            return refuse(callId, toolId, descriptor.rank(), Disposition.INVALID,
                    SegmentToolErrorCodes.TOOL_OUT_OF_ORDER, inputHash);
        }
        if (canonicalSize(payload) > Math.min(descriptor.maxPayloadBytes(), limits.maxToolPayloadBytes())) {
            return refuse(callId, toolId, descriptor.rank(), Disposition.INVALID,
                    SegmentToolErrorCodes.TOOL_PAYLOAD_LIMIT_EXCEEDED, inputHash);
        }
        SegmentToolHandler handler = handlers.get(toolId);
        if (handler == null) {
            return refuse(callId, toolId, descriptor.rank(), Disposition.INVALID,
                    SegmentToolErrorCodes.TOOL_NOT_IMPLEMENTED, inputHash);
        }

        // Admit the call before running the implementation: a call that reached the handler is recorded exactly
        // once, and a later duplicate or out-of-order attempt is refused deterministically.
        usedCallIds.add(callId);
        usedToolIds.add(toolId);
        lastRank = descriptor.rank();
        callCount++;

        SegmentToolExecution execution;
        try {
            execution = handler.execute(run, payload);
        } catch (RuntimeException failure) {
            execution = SegmentToolExecution.failed(Disposition.INVALID, SegmentToolHandler.FAILURE_CODE);
        }
        if (execution == null) {
            return refuse(callId, toolId, descriptor.rank(), Disposition.INVALID,
                    SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT, inputHash);
        }
        if (!execution.disposition().isResolved()) {
            record(toolId, descriptor.rank(), execution.disposition(), execution.code(), inputHash);
            return new SegmentToolAdmission(
                    callId, toolId, catalog.skillHash(),
                    failure(callId, toolId, execution.disposition(), execution.code(), inputHash),
                    execution.artifact());
        }
        SegmentToolOutcome outcome = execution.outcome();
        String incoherent = requireCoherentOutcome(toolId, descriptor, outcome, execution.artifact());
        if (incoherent != null) {
            return refuse(callId, toolId, descriptor.rank(), Disposition.INVALID, incoherent, inputHash);
        }
        record(toolId, descriptor.rank(), Disposition.RESOLVED, null, inputHash);
        return new SegmentToolAdmission(
                callId, toolId, catalog.skillHash(),
                ToolResultEnvelope.resolved(callId, toolId, outcome, inputHash, List.<Issue>of()),
                execution.artifact());
    }

    /** Marks the run terminal so every later Tool call fails closed. */
    public void markTerminal() {
        this.terminal = true;
    }

    /** The immutable audit-safe trace of every Tool attempt of this run, in call order. */
    public List<SegmentToolCallRecord> trace() {
        return List.copyOf(trace);
    }

    /** How many Tool calls this run admitted. */
    public int callCount() {
        return callCount;
    }

    /** The catalog this run used. */
    public SegmentToolCatalog catalog() {
        return catalog;
    }

    private String requireCoherentOutcome(
            String toolId, SegmentToolDescriptor descriptor, SegmentToolOutcome outcome, SegmentToolArtifact artifact) {
        if (outcome == null || !toolId.equals(outcome.toolId())) {
            return SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT;
        }
        if (outcome.itemCount() > descriptor.maxResultItems()) {
            return SegmentToolErrorCodes.TOOL_OUTPUT_LIMIT_EXCEEDED;
        }
        if (CANONICAL_JSON.write(outcome).length > MAX_OUTCOME_BYTES) {
            return SegmentToolErrorCodes.TOOL_OUTPUT_LIMIT_EXCEEDED;
        }
        if (artifact != null) {
            if (!toolId.equals(artifact.toolId())) {
                return SegmentToolErrorCodes.TOOL_ARTIFACT_INCOHERENT;
            }
            if (!outcome.evidenceHash().equals(artifact.publicEvidenceHash())) {
                return SegmentToolErrorCodes.TOOL_ARTIFACT_INCOHERENT;
            }
        }
        return null;
    }

    private int canonicalSize(ToolInput payload) {
        try {
            return CANONICAL_JSON.write(payload.arguments()).length;
        } catch (RuntimeException failure) {
            // A payload that cannot be canonically serialized is over any bound and never reaches a handler.
            return Integer.MAX_VALUE;
        }
    }

    private SegmentToolAdmission refuse(
            String callId, String toolId, int rank, Disposition disposition, String code, ContentHash inputHash) {
        record(toolId, rank, disposition, code, inputHash);
        return new SegmentToolAdmission(
                callId, toolId, catalog.skillHash(), failure(callId, toolId, disposition, code, inputHash), null);
    }

    private void record(String toolId, int rank, Disposition disposition, String code, ContentHash inputHash) {
        trace.add(new SegmentToolCallRecord(toolId, rank, disposition, code, inputHash));
    }

    private static ToolResultEnvelope<SegmentToolOutcome> failure(
            String callId, String toolId, Disposition disposition, String code, ContentHash inputHash) {
        return ToolResultEnvelope.unresolved(
                callId, toolId, disposition, ToolFailure.of(code, messageFor(code)), inputHash);
    }

    private static String messageFor(String code) {
        return "The Tool call was refused with the bounded code " + code;
    }

    private static void requireEnvelope(ToolEnvelope envelope) {
        if (envelope == null) {
            throw SegmentToolException.invalid(
                    SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT, "A Tool call requires an envelope");
        }
        if (envelope.toolCallId() == null || !envelope.toolCallId().matches(ContractPatterns.REFERENCE_ID)) {
            throw SegmentToolException.invalid(
                    SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT, "A Tool call requires a bounded call identifier");
        }
        if (envelope.toolId() == null || !envelope.toolId().matches(ContractPatterns.REFERENCE_ID)) {
            throw SegmentToolException.invalid(
                    SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT, "A Tool call requires a bounded Tool identifier");
        }
    }

    /** The immutable, catalog-derived bounds one run must respect. */
    private record SegmentToolLimitsSnapshot(int maxToolCalls, int maxToolPayloadBytes) {
    }
}