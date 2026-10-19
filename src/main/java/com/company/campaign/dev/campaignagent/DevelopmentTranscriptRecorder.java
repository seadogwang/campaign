package com.company.campaign.dev.campaignagent;

import com.company.campaign.builder.campaignagent.CampaignClarificationAnswer;
import com.company.campaign.builder.campaignagent.http.CampaignAgentTurnHttpResponse;
import com.company.campaign.shared.canonical.CanonicalJson;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * The one durable, development-only development transcript of the opt-in local Campaign Agent host.
 *
 * <p>For every server-issued {@code conversationId} it appends exactly one UTF-8 JSON Lines file under the
 * host-named directory, so a real development/acceptance API debugging session survives the process that
 * produced it. It is explicitly not production audit behavior: the host must set
 * {@code campaign.agent.development.transcript.enabled=true} <em>and</em> name a directory, it is disabled by
 * default, it exists only inside the development Campaign host configuration, and it never touches the accepted
 * production audit redaction contract.
 *
 * <p>The file is durable debugging evidence only. Nothing here is read back as execution state, authentication
 * state, resume state or model input, and the PostgreSQL conversation store remains the source of truth for
 * resumable production state. The two are independent: the transcript can be deleted without changing a turn.
 *
 * <p><strong>Privacy.</strong> The transcript deliberately contains sensitive raw text — the submitted user
 * messages, the typed clarification answers and the raw model response documents — because that is its whole
 * purpose. It must never be treated as a production artifact, never be shipped to an audit ledger, never carry
 * credentials, authorization headers, endpoint URLs, model configuration, HTTP headers, stack traces, exception
 * causes, SQL, member identifiers or provider secrets, and never be enabled in production.
 *
 * <p>Security properties:
 * <ul>
 *   <li>the file name is only the validated opaque conversation identifier; the accepted identifier pattern is
 *       narrowed further to {@code [A-Za-z0-9._-]} so no path separator, drive designator, alternate-data-stream
 *       colon or traversal component can be expressed, and the resolved path is proven to stay directly inside
 *       the host-named real directory;</li>
 *   <li>the configured root is created and then resolved to its real path, a symbolic link at the root or at a
 *       conversation file is refused, and an existing non-regular file is refused;</li>
 *   <li>every event is appended as one complete {@code JSON + '\n'} buffer with {@code CREATE|APPEND}, so an
 *       earlier complete event is never overwritten and a torn tail can never corrupt a previous line;</li>
 *   <li>per-conversation locks plus a per-conversation sequence keep the appended order stable under concurrent
 *       requests, and an in-memory sequence is re-seeded from the existing line count so a restart continues the
 *       numbering instead of repeating it;</li>
 *   <li>an event and a whole conversation file are both bounded, and an append that would exceed a bound is
 *       refused with a bounded diagnostic rather than being written partially.</li>
 * </ul>
 *
 * <p>A refusal is never silent. An unsafe identifier is raised as a bounded
 * {@link DevelopmentTranscriptException}; an oversized event, an exhausted file or a filesystem failure is
 * counted in {@link #diagnostics()} and reported by returning {@code false}, so no caller can claim a transcript
 * was written when it was not.
 */
public final class DevelopmentTranscriptRecorder {

    /** The self-describing format identifier every event carries, so a reader can version the file. */
    public static final String FORMAT = "campaign-agent-development-transcript/1";

    /** The event type of one server-issued conversation creation. */
    public static final String CONVERSATION_CREATED = "CONVERSATION_CREATED";

    /** The event type of one submitted user turn, with its message and typed clarification answers. */
    public static final String TURN_SUBMITTED = "TURN_SUBMITTED";

    /** The event type of one raw model response document. */
    public static final String MODEL_RESPONSE = "MODEL_RESPONSE";

    /** The event type of one accepted or refused turn result, with the bounded API projection. */
    public static final String TURN_RESULT = "TURN_RESULT";

    /** The model call kind of one Campaign routing-model response. */
    public static final String ROUTING = "ROUTING";

    /** The model call kind of the single bounded Campaign routing schema-repair response. */
    public static final String ROUTING_SCHEMA_REPAIR = "ROUTING_SCHEMA_REPAIR";

    /** The model call kind of one Campaign Skill input-resolution response. */
    public static final String SKILL_INPUT = "SKILL_INPUT";

    /** The model call kind of one Segment Intent/model response. */
    public static final String SEGMENT_INTENT = "SEGMENT_INTENT";

    /** The model call kind of the single bounded Segment Intent schema-repair response. */
    public static final String SEGMENT_INTENT_SCHEMA_REPAIR = "SEGMENT_INTENT_SCHEMA_REPAIR";

    /** The one file suffix of a conversation transcript. */
    public static final String FILE_SUFFIX = ".jsonl";

    /** The declared bound on one complete appended event, including its newline. */
    public static final int MAX_EVENT_BYTES = 1_048_576;

    /** The declared per-conversation file bound; an append that would exceed it is refused, not truncated. */
    public static final long MAX_FILE_BYTES = 16L * 1024 * 1024;

    static final String DIAGNOSTIC_EVENT_UNWRITABLE = "transcript_event_unwritable";
    static final String DIAGNOSTIC_EVENT_OVERSIZED = "transcript_event_oversized";
    static final String DIAGNOSTIC_FILE_LIMIT = "transcript_file_limit_reached";
    static final String DIAGNOSTIC_WRITE_FAILED = "transcript_write_failed";
    static final String DIAGNOSTIC_RESPONSE_BOUNDS = "transcript_model_response_out_of_bounds";
    static final String DIAGNOSTIC_PATH_UNSAFE = "transcript_path_unsafe";

    /**
     * The narrowed file-name pattern: the accepted reference-identifier shape minus {@code :} and every path
     * separator, so a Windows drive designator, an NTFS alternate data stream or a traversal component cannot be
     * expressed even before the path is resolved.
     */
    private static final Pattern FILE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    private final boolean enabled;
    private final Path directory;
    private final Clock clock;
    private final CanonicalJson json = new CanonicalJson();
    private final ConcurrentMap<String, ConversationLog> logs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> diagnostics = new ConcurrentHashMap<>();

    private DevelopmentTranscriptRecorder(boolean enabled, Path directory, Clock clock) {
        this.enabled = enabled;
        this.directory = directory;
        this.clock = clock;
    }

    /** The inert recorder of a host that did not opt in: it holds no directory and writes nothing at all. */
    public static DevelopmentTranscriptRecorder disabled() {
        return new DevelopmentTranscriptRecorder(false, null, Clock.systemUTC());
    }

    /**
     * Builds the one recorder of the opt-in development host from the validated host properties.
     *
     * <p>A disabled or absent configuration yields the inert recorder, so the development host starts exactly as
     * before. An enabled configuration must name a directory that can be created and resolved; an absent,
     * malformed, unusable or symbolic-link directory fails the host startup closed with one bounded code and a
     * message that carries no path, so the host never silently records nothing while claiming to record.
     */
    public static DevelopmentTranscriptRecorder of(
            DevelopmentCampaignAgentProperties.Transcript properties, Clock clock) {
        Objects.requireNonNull(clock, "clock");
        if (properties == null || !properties.isEnabled()) {
            return new DevelopmentTranscriptRecorder(false, null, clock);
        }
        String named = properties.getDirectory();
        if (named == null || named.isBlank()) {
            throw unavailable();
        }
        Path configured;
        try {
            configured = Path.of(named).toAbsolutePath().normalize();
        } catch (InvalidPathException failure) {
            throw unavailable();
        }
        try {
            if (Files.isSymbolicLink(configured)) {
                // A symbolic link at the configured root could redirect every transcript outside the boundary
                // the host named, so the host refuses to start rather than follow it.
                throw unavailable();
            }
            Files.createDirectories(configured);
            if (!Files.isDirectory(configured)) {
                throw unavailable();
            }
            // The real path is the containment root every file must resolve directly inside, so a symlinked
            // ancestor cannot make a resolved file look contained when it is not.
            return new DevelopmentTranscriptRecorder(true, configured.toRealPath(), clock);
        } catch (IOException failure) {
            throw unavailable();
        }
    }

    private static DevelopmentCampaignAgentConfigurationException unavailable() {
        return new DevelopmentCampaignAgentConfigurationException(
                DevelopmentCampaignAgentErrorCodes.CONFIGURATION_INVALID,
                "The development transcript directory could not be created, is not a directory or is a "
                        + "symbolic link.");
    }

    /** True only when the host explicitly enabled and named the development transcript. */
    public boolean isEnabled() {
        return enabled;
    }

    /** A bounded, value-free snapshot of every refused or failed append; never a path, an identifier or a byte. */
    public Map<String, Long> diagnostics() {
        Map<String, Long> snapshot = new TreeMap<>();
        diagnostics.forEach((code, count) -> snapshot.put(code, count.get()));
        return Collections.unmodifiableMap(snapshot);
    }

    // ------------------------------------------------------------------ the recorded events

    /** Records one server-issued conversation creation; no trusted tenant, program or user value is included. */
    public boolean recordConversationCreated(String conversationId, String runId, long revision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("revision", revision);
        return append(conversationId, runId, CONVERSATION_CREATED, body);
    }

    /**
     * Records one submitted user turn: its message and every typed clarification answer, in order.
     *
     * <p>The message and the answer texts are the user's own submitted conversation, which is exactly what the
     * debugging transcript exists to preserve; they carry no tenant, user, member or credential value.
     */
    public boolean recordTurnSubmitted(
            String conversationId,
            String runId,
            long expectedRevision,
            String message,
            List<CampaignClarificationAnswer> answers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedRevision", expectedRevision);
        body.put("message", message == null ? "" : message);
        List<Map<String, Object>> encoded = new ArrayList<>();
        List<CampaignClarificationAnswer> bounded = answers == null ? List.of() : answers;
        for (CampaignClarificationAnswer answer : bounded) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", answer.code());
            entry.put("optionId", answer.optionId());
            entry.put("freeText", answer.freeText());
            entry.put("component", answer.component() == null ? null : answer.component().name());
            encoded.add(entry);
        }
        body.put("answers", encoded);
        return append(conversationId, runId, TURN_SUBMITTED, body);
    }

    /**
     * Records one raw model response document for the conversation currently being served on this thread.
     *
     * <p>The bytes are stored exactly as UTF-8, and only after the same size and strict-UTF-8 boundary the
     * calling site applies accepts them: an oversized or non-UTF-8 response is refused with a bounded diagnostic
     * rather than stored as a lossy substitute. Only the response document is stored — never the prompt, the
     * endpoint, a header, a credential or a model name.
     *
     * @param modelCallKind the closed call kind, such as {@link #ROUTING} or {@link #SEGMENT_INTENT}
     * @param raw           the already-bounded raw response bytes, or {@code null} for an empty response
     * @param maxBytes      the accepted bound of the calling site
     */
    public boolean recordModelResponse(String modelCallKind, byte[] raw, int maxBytes) {
        if (!enabled || raw == null || raw.length == 0) {
            return false;
        }
        DevelopmentTranscriptScope.Identity identity = DevelopmentTranscriptScope.current();
        if (identity == null) {
            // No conversation is being served on this thread, so this call cannot be attributed and is skipped.
            return false;
        }
        if (raw.length > maxBytes) {
            diagnose(DIAGNOSTIC_RESPONSE_BOUNDS);
            return false;
        }
        String text = strictUtf8(raw);
        if (text == null || text.isEmpty()) {
            diagnose(DIAGNOSTIC_RESPONSE_BOUNDS);
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelCallKind", modelCallKind);
        body.put("responseBytes", raw.length);
        body.put("responseText", text);
        return append(identity.conversationId(), identity.runId(), MODEL_RESPONSE, body);
    }

    /** Records one accepted or refused turn result, including the bounded API projection the route returned. */
    public boolean recordTurnResult(CampaignAgentTurnHttpResponse response) {
        if (response == null) {
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", response.accepted());
        body.put("code", response.code());
        body.put("revision", response.revision());
        body.put("phase", response.phase());
        body.put("disposition", response.disposition());
        body.put("routeKind", response.routeKind());
        body.put("projection", response);
        return append(response.conversationId(), response.runId(), TURN_RESULT, body);
    }

    /**
     * Records one bounded turn failure that produced no projection.
     *
     * <p>Only the stable machine code is recorded — never the exception type, message, cause or stack trace, so a
     * failed turn is still evidence without becoming a disclosure channel.
     */
    public boolean recordTurnFailure(String conversationId, String runId, String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", false);
        body.put("code", code);
        return append(conversationId, runId, TURN_RESULT, body);
    }

    // ------------------------------------------------------------------ the append

    private boolean append(String conversationId, String runId, String type, Map<String, Object> body) {
        if (!enabled) {
            return false;
        }
        ConversationLog log = logs.computeIfAbsent(requireSafeName(conversationId), key -> new ConversationLog());
        synchronized (log.lock) {
            Path file = fileFor(conversationId);
            long sequence = log.nextSequence(file);
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("format", FORMAT);
            event.put("sequence", sequence);
            event.put("at", clock.instant().toString());
            event.put("type", type);
            event.put("conversationId", conversationId);
            event.put("runId", runId);
            event.putAll(body);

            byte[] line;
            try {
                line = json.write(event);
            } catch (RuntimeException failure) {
                diagnose(DIAGNOSTIC_EVENT_UNWRITABLE);
                return false;
            }
            if (line.length + 1 > MAX_EVENT_BYTES) {
                diagnose(DIAGNOSTIC_EVENT_OVERSIZED);
                return false;
            }
            byte[] record = Arrays.copyOf(line, line.length + 1);
            record[line.length] = '\n';
            try {
                long size = Files.exists(file, LinkOption.NOFOLLOW_LINKS) ? Files.size(file) : 0L;
                if (size + record.length > MAX_FILE_BYTES) {
                    diagnose(DIAGNOSTIC_FILE_LIMIT);
                    return false;
                }
                // One complete buffer with CREATE|APPEND: an earlier complete event is never overwritten, and a
                // concurrent appender can only add its own complete line after this one.
                Files.write(file, record, StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                        StandardOpenOption.WRITE);
                return true;
            } catch (IOException failure) {
                diagnose(DIAGNOSTIC_WRITE_FAILED);
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ bounded, contained file naming

    private String requireSafeName(String conversationId) {
        if (conversationId == null || !FILE_NAME.matcher(conversationId).matches()
                || ".".equals(conversationId) || "..".equals(conversationId)) {
            diagnose(DIAGNOSTIC_PATH_UNSAFE);
            throw DevelopmentTranscriptException.unsafeConversationIdentifier();
        }
        return conversationId;
    }

    private Path fileFor(String conversationId) {
        requireSafeName(conversationId);
        Path file = directory.resolve(conversationId + FILE_SUFFIX).normalize();
        // The pattern already excludes every separator, so this is defence in depth: the resolved file must sit
        // directly inside the host-named real directory and nowhere else.
        if (!directory.equals(file.getParent()) || !file.startsWith(directory)) {
            diagnose(DIAGNOSTIC_PATH_UNSAFE);
            throw DevelopmentTranscriptException.unsafeConversationIdentifier();
        }
        try {
            if (Files.isSymbolicLink(file)
                    || (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file))) {
                diagnose(DIAGNOSTIC_PATH_UNSAFE);
                throw DevelopmentTranscriptException.unsafeConversationIdentifier();
            }
        } catch (SecurityException failure) {
            diagnose(DIAGNOSTIC_PATH_UNSAFE);
            throw DevelopmentTranscriptException.unsafeConversationIdentifier();
        }
        return file;
    }

    private void diagnose(String code) {
        diagnostics.computeIfAbsent(code, key -> new AtomicLong()).incrementAndGet();
    }

    /** The exact UTF-8 text of a raw response, or null when it is not strict UTF-8. */
    private static String strictUtf8(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString();
        } catch (CharacterCodingException failure) {
            return null;
        }
    }

    /** The mutable per-conversation append state: one lock and one monotonically increasing event sequence. */
    private static final class ConversationLog {

        private final Object lock = new Object();
        private boolean seeded;
        private long sequence = 1L;

        private long nextSequence(Path file) {
            if (!seeded) {
                sequence = countEvents(file) + 1L;
                seeded = true;
            }
            long current = sequence;
            sequence = current + 1L;
            return current;
        }

        /** Continues the numbering of an existing file rather than repeating it after a restart. */
        private static long countEvents(Path file) {
            try {
                if (!Files.isRegularFile(file)) {
                    return 0L;
                }
                byte[] existing = Files.readAllBytes(file);
                long count = 0L;
                for (byte value : existing) {
                    if (value == '\n') {
                        count++;
                    }
                }
                return count;
            } catch (IOException failure) {
                return 0L;
            }
        }
    }

    /** A bounded diagnostic: the enablement and the format only, never a directory or an identifier. */
    @Override
    public String toString() {
        return "DevelopmentTranscriptRecorder[enabled=" + enabled + ", format=" + FORMAT + ']';
    }
}