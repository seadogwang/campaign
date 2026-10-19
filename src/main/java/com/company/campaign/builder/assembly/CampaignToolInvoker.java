package com.company.campaign.builder.assembly;

import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.Issue;
import com.company.campaign.contracts.ToolFailure;
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
 * The host-owned closed Tool invoker of one Skill selection in one run.
 *
 * <p>The invoker is the single control point of a protected stage: it decides whether a call is admitted
 * <em>before</em> the accepted catalog or capability-pack implementation is entered, and it hands the accepted
 * typed result back through {@link CampaignToolAdmission}. A caller cannot register a Tool, choose an
 * implementation, override the catalog allowlist or supply trusted context: the invoker resolves the exact
 * catalog descriptor and host run evidence itself, and every handler reads only host-owned evidence.
 *
 * <p>Every attempt is judged against the per-run call count, the canonical input size, the Tool's own declared
 * availability and model-callability, the exact catalog hash and Tool version, the owning Skill, the declared
 * phase order and at-most-once Tool use, and every attempt — admitted or refused — appends exactly one
 * audit-safe trace entry carrying the redacted canonical request fingerprint before returning. Unknown,
 * forbidden, unavailable, wrong-Skill, version-mismatched, duplicate, out-of-order, over-limit and post-run
 * calls therefore fail closed before any implementation runs.
 *
 * <p>There is no generic reflection, script, URL, repository, JDBC or arbitrary-JSON callback Tool, and
 * Save/Apply/Publish/Pause/Resume/Retry/Archive/Runtime are host-only and cannot be named here.
 *
 * <p>One invoker belongs to exactly one Skill selection: it holds the run's call bookkeeping that must never be
 * shared or carried across selections.
 */
public final class CampaignToolInvoker {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final CampaignToolCatalog catalog;
    private final String skillId;
    private final Map<String, CampaignToolHandler> handlers;
    private final CampaignToolRunContext run;
    private final int maxToolCalls;

    private final Set<String> usedCallIds = new LinkedHashSet<>();
    private final Set<String> usedToolIds = new LinkedHashSet<>();
    private final List<CampaignToolTraceEntry> trace = new ArrayList<>();
    private int lastRank = -1;
    private int callCount;
    private boolean terminal;

    public CampaignToolInvoker(
            CampaignToolCatalog catalog,
            String skillId,
            Map<String, CampaignToolHandler> handlers,
            CampaignToolRunContext run) {
        this(catalog, skillId, handlers, run, catalog == null ? 0 : catalog.maxToolCalls());
    }

    /**
     * A host-owned invoker under an explicit per-run call bound no greater than the catalog's own.
     *
     * <p>The bound can only tighten the catalog's declared maximum, so a host cannot widen the closed
     * vocabulary; the catalog remains the sole authority on which Tools exist.
     */
    public CampaignToolInvoker(
            CampaignToolCatalog catalog,
            String skillId,
            Map<String, CampaignToolHandler> handlers,
            CampaignToolRunContext run,
            int maxToolCalls) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        if (skillId == null || !CampaignSkillIds.isKnown(skillId)) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_WRONG_SKILL, "An invoker requires a server-owned Skill.");
        }
        if (maxToolCalls < 1) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_CALL_LIMIT_EXCEEDED, "An invoker requires a positive call bound.");
        }
        this.skillId = skillId;
        this.handlers = handlers == null ? Map.of() : Map.copyOf(handlers);
        this.run = run == null ? new CampaignToolRunContext() {
        } : run;
        this.maxToolCalls = Math.min(maxToolCalls, catalog.maxToolCalls());
    }

    /** Invokes one host-orchestrated typed Tool call and returns its bound admission. */
    public CampaignToolAdmission invoke(CampaignToolCall call) {
        if (call == null) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT, "A Tool call requires an envelope.");
        }
        String callId = call.toolCallId();
        String toolId = call.toolId();
        ContentHash inputHash = fingerprint(call.request());

        if (terminal) {
            return refuse(callId, toolId, call.toolVersion(), CampaignToolTraceEntry.UNKNOWN_RANK,
                    Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_POST_TERMINAL, inputHash);
        }
        if (!ContractVersions.isCurrent(call.schemaVersion())) {
            return refuse(callId, toolId, call.toolVersion(), CampaignToolTraceEntry.UNKNOWN_RANK,
                    Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_SCHEMA_STALE, inputHash);
        }
        if (CampaignToolIds.attemptsForbiddenCapability(toolId)) {
            return refuse(callId, toolId, call.toolVersion(), CampaignToolTraceEntry.UNKNOWN_RANK,
                    Disposition.DENIED, CampaignAssemblyErrorCodes.TOOL_FORBIDDEN_CAPABILITY, inputHash);
        }
        CampaignToolDescriptor descriptor = catalog.descriptor(toolId);
        if (descriptor == null) {
            return refuse(callId, toolId, call.toolVersion(), CampaignToolTraceEntry.UNKNOWN_RANK,
                    Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_UNKNOWN, inputHash);
        }
        if (!descriptor.available()) {
            return refuse(callId, toolId, call.toolVersion(), descriptor.rank(),
                    Disposition.UNSUPPORTED, CampaignAssemblyErrorCodes.TOOL_UNAVAILABLE, inputHash);
        }
        if (!descriptor.modelCallable()) {
            return refuse(callId, toolId, call.toolVersion(), descriptor.rank(),
                    Disposition.DENIED, CampaignAssemblyErrorCodes.TOOL_NOT_MODEL_CALLABLE, inputHash);
        }
        if (!descriptor.owningSkillId().equals(skillId)) {
            return refuse(callId, toolId, call.toolVersion(), descriptor.rank(),
                    Disposition.DENIED, CampaignAssemblyErrorCodes.TOOL_WRONG_SKILL, inputHash);
        }
        if (!descriptor.toolVersion().equals(call.toolVersion())) {
            return refuse(callId, toolId, call.toolVersion(), descriptor.rank(),
                    Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_VERSION_MISMATCH, inputHash);
        }
        if (!call.coherent()) {
            // The envelope must carry the typed request of exactly the Tool it names; an envelope describing one
            // Tool while carrying another Tool's body is refused before any implementation runs.
            return refuse(callId, toolId, call.toolVersion(), descriptor.rank(),
                    Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT, inputHash);
        }
        if (usedCallIds.contains(callId) || usedToolIds.contains(toolId)) {
            return refuse(callId, toolId, call.toolVersion(), descriptor.rank(),
                    Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_DUPLICATE_CALL, inputHash);
        }
        if (callCount >= maxToolCalls) {
            return refuse(callId, toolId, call.toolVersion(), descriptor.rank(),
                    Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_CALL_LIMIT_EXCEEDED, inputHash);
        }
        if (descriptor.rank() <= lastRank || (callCount == 0 && descriptor.rank() != firstRankOfSkill())) {
            // The first admitted call must be the Skill's first permitted Tool, and every later call must
            // strictly advance the rank. A non-consecutive rank is accepted: only the host orchestrator
            // decides which declared phases run.
            return refuse(callId, toolId, call.toolVersion(), descriptor.rank(),
                    Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_OUT_OF_ORDER, inputHash);
        }
        if (canonicalSize(call.request()) > Math.min(descriptor.maxInputBytes(), CampaignToolCatalog.MAX_INPUT_BYTES)) {
            return refuse(callId, toolId, call.toolVersion(), descriptor.rank(),
                    Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_INPUT_LIMIT_EXCEEDED, inputHash);
        }
        CampaignToolHandler handler = handlers.get(toolId);
        if (handler == null) {
            return refuse(callId, toolId, call.toolVersion(), descriptor.rank(),
                    Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_NOT_IMPLEMENTED, inputHash);
        }

        // Admit the call before running the implementation: a call that reached the handler is recorded exactly
        // once, and a later duplicate or out-of-order attempt is refused deterministically.
        usedCallIds.add(callId);
        usedToolIds.add(toolId);
        lastRank = descriptor.rank();
        callCount++;

        CampaignToolExecution execution;
        try {
            execution = handler.execute(run, call.request());
        } catch (RuntimeException failure) {
            execution = CampaignToolExecution.failed(Disposition.INVALID, CampaignToolHandler.FAILURE_CODE);
        }
        if (execution == null) {
            return refuse(callId, toolId, call.toolVersion(), descriptor.rank(),
                    Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT, inputHash);
        }
        if (!execution.disposition().isResolved()) {
            record(toolId, descriptor.rank(), execution.disposition(), execution.code(), inputHash);
            return new CampaignToolAdmission(
                    callId, toolId, descriptor.toolVersion(), catalog.catalogHash(),
                    failure(callId, toolId, execution.disposition(), execution.code(), inputHash),
                    execution.artifact());
        }
        CampaignToolOutcome outcome = execution.outcome();
        String incoherent = requireCoherentOutcome(toolId, descriptor, outcome, execution.artifact());
        if (incoherent != null) {
            return refuse(callId, toolId, call.toolVersion(), descriptor.rank(),
                    Disposition.INVALID, incoherent, inputHash);
        }
        record(toolId, descriptor.rank(), Disposition.RESOLVED, null, inputHash);
        return new CampaignToolAdmission(
                callId, toolId, descriptor.toolVersion(), catalog.catalogHash(),
                ToolResultEnvelope.resolved(callId, toolId, outcome, inputHash, List.<Issue>of()),
                execution.artifact());
    }

    /** Marks the run terminal so every later Tool call fails closed. */
    public void markTerminal() {
        this.terminal = true;
    }

    /** The immutable audit-safe trace of every Tool attempt of this run, in call order. */
    public List<CampaignToolTraceEntry> trace() {
        return List.copyOf(trace);
    }

    /** How many Tool calls this run admitted. */
    public int callCount() {
        return callCount;
    }

    /** The catalog this run used. */
    public CampaignToolCatalog catalog() {
        return catalog;
    }

    /** The selected Skill this invoker admits Tools for. */
    public String skillId() {
        return skillId;
    }

    /** The lowest declared rank of the selected Skill, which the first admitted call must carry. */
    private int firstRankOfSkill() {
        int lowest = Integer.MAX_VALUE;
        for (CampaignToolDescriptor descriptor : catalog.ownedBy(skillId)) {
            lowest = Math.min(lowest, descriptor.rank());
        }
        return lowest;
    }

    private String requireCoherentOutcome(
            String toolId, CampaignToolDescriptor descriptor, CampaignToolOutcome outcome,
            CampaignToolArtifact artifact) {
        if (outcome == null || !toolId.equals(outcome.toolId())) {
            return CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT;
        }
        if (outcome.itemCount() > CampaignToolDescriptor.MAX_RESULT_ITEMS) {
            return CampaignAssemblyErrorCodes.TOOL_RESULT_LIMIT_EXCEEDED;
        }
        if (artifact != null) {
            if (!toolId.equals(artifact.toolId())) {
                return CampaignAssemblyErrorCodes.TOOL_ARTIFACT_INCOHERENT;
            }
            if (!outcome.evidenceHash().equals(artifact.publicEvidenceHash())) {
                return CampaignAssemblyErrorCodes.TOOL_ARTIFACT_INCOHERENT;
            }
        }
        return null;
    }

    private static int canonicalSize(CampaignToolRequest request) {
        try {
            return CANONICAL_JSON.write(request.canonicalArguments()).length;
        } catch (RuntimeException failure) {
            // A request that cannot be canonically serialized is over any bound and never reaches a handler.
            return Integer.MAX_VALUE;
        }
    }

    private static ContentHash fingerprint(CampaignToolRequest request) {
        if (request == null) {
            return CANONICAL_JSON.hash(Map.of());
        }
        try {
            return CANONICAL_JSON.hash(request.canonicalArguments());
        } catch (RuntimeException failure) {
            return CANONICAL_JSON.hash(Map.of("unbounded", true));
        }
    }

    private CampaignToolAdmission refuse(
            String callId, String toolId, String toolVersion, int rank,
            Disposition disposition, String code, ContentHash inputHash) {
        record(toolId, rank, disposition, code, inputHash);
        String version = toolVersion == null ? "1.0" : toolVersion;
        return new CampaignToolAdmission(
                callId, toolId, version, catalog.catalogHash(),
                failure(callId, toolId, disposition, code, inputHash), null);
    }

    private void record(String toolId, int rank, Disposition disposition, String code, ContentHash inputHash) {
        trace.add(new CampaignToolTraceEntry(toolId, rank, disposition, code, inputHash));
    }

    private static ToolResultEnvelope<CampaignToolOutcome> failure(
            String callId, String toolId, Disposition disposition, String code, ContentHash inputHash) {
        return ToolResultEnvelope.unresolved(
                callId, toolId, disposition, ToolFailure.of(code, messageFor(code)), inputHash);
    }

    private static String messageFor(String code) {
        return "The Tool call was refused with the bounded code " + code;
    }

    /** One immutable audit-safe record of a Tool attempt, whether it was admitted or refused. */
    public record CampaignToolTraceEntry(
            String toolId,
            int rank,
            Disposition disposition,
            String code,
            ContentHash inputFingerprint) {

        /** The rank recorded for an attempt that named a Tool the catalog does not order for this Skill. */
        public static final int UNKNOWN_RANK = -1;

        public CampaignToolTraceEntry {
            if (toolId == null || disposition == null || inputFingerprint == null) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A Tool trace entry requires its identity.");
            }
            if (disposition.isResolved() == (code != null)) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                        "Only a non-resolved Tool trace entry may carry a code.");
            }
        }
    }
}