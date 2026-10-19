package com.company.campaign.fullpublish;

import com.company.campaign.builder.capability.schedule.ScheduleBasicVersions;
import com.company.campaign.journey.compiler.JourneyVersions;

import java.time.Duration;
import java.util.List;

/**
 * Every version discriminator, closed code and frozen bound owned by the T21 full-publication and durable
 * scheduling layer.
 *
 * <p>Each value here is part of a frozen artifact's canonical hash — a full preparation, a full definition, a
 * full receipt, an active schedule projection, a durable occurrence record or a scheduling-registration outbox
 * event — so changing any of them is a new version rather than a silent reinterpretation of already-frozen
 * bytes.
 *
 * <p>The Journey and Schedule compiler/format versions are not restated here: they are the accepted T20 and
 * T21 discriminators themselves, so the full layer can never claim a version the compiler it calls does not
 * produce.
 */
public final class FullPublishVersions {

    /** The server-stored full publication preparation document format version. */
    public static final String FULL_PREPARATION_FORMAT_VERSION = "campaign-full-publish-preparation/1.0";

    /** The immutable full publication definition document format version. */
    public static final String FULL_DEFINITION_FORMAT_VERSION = "campaign-full-published-definition/1.0";

    /** The immutable full publication receipt document format version. */
    public static final String FULL_RECEIPT_FORMAT_VERSION = "campaign-full-publish-receipt/1.0";

    /** The immutable active schedule projection document format version. */
    public static final String SCHEDULE_STATE_FORMAT_VERSION = "campaign-schedule-state/1.0";

    /** The immutable committed-occurrence document format version. */
    public static final String OCCURRENCE_RECORD_FORMAT_VERSION = "campaign-schedule-occurrence-work/1.0";

    /** The immutable scheduling-registration outbox event/work-item format version. */
    public static final String OUTBOX_FORMAT_VERSION = "campaign-schedule-registration-outbox/1.0";

    /** The immutable host runtime-availability pin format version. */
    public static final String RUNTIME_AVAILABILITY_FORMAT_VERSION = "campaign-runtime-availability/1.0";

    /** The accepted T20 Journey format version a full publication freezes. */
    public static final String JOURNEY_FORMAT_VERSION = JourneyVersions.JOURNEY_FORMAT_VERSION;

    /** The accepted T20 Journey compiler version a full publication freezes. */
    public static final String JOURNEY_COMPILER_VERSION = JourneyVersions.JOURNEY_COMPILER_VERSION;

    /** The accepted runtime schema version both the Journey and the Schedule must declare. */
    public static final String RUNTIME_SCHEMA_VERSION = JourneyVersions.JOURNEY_RUNTIME_SCHEMA_VERSION;

    /** The accepted T21 schedule format version a full publication freezes. */
    public static final String SCHEDULE_FORMAT_VERSION = ScheduleVersions.SCHEDULE_FORMAT_VERSION;

    /** The accepted T21 schedule compiler version a full publication freezes. */
    public static final String SCHEDULE_COMPILER_VERSION = ScheduleVersions.SCHEDULE_COMPILER_VERSION;

    /** The accepted {@code schedule.basic} capability code a full publication requires exactly one of. */
    public static final String SCHEDULE_CAPABILITY_CODE = ScheduleBasicVersions.CAPABILITY_CODE;

    /** The accepted {@code schedule.basic} contract version a full publication requires exactly one of. */
    public static final String SCHEDULE_CONTRACT_VERSION = ScheduleBasicVersions.CONTRACT_VERSION;

    /** The default full preparation lifetime; a host may inject a shorter or longer one within the bound. */
    public static final Duration DEFAULT_PREPARATION_LIFETIME = Duration.ofMinutes(15);

    /** The longest lifetime any full preparation may declare, so it cannot outlive its own inputs. */
    public static final Duration MAX_PREPARATION_LIFETIME = Duration.ofMinutes(60);

    /** The shortest lifetime any full preparation may declare, so it is never born already spent. */
    public static final Duration MIN_PREPARATION_LIFETIME = Duration.ofSeconds(30);

    /**
     * The bounded risk codes a full publication always requires, in addition to the base preparation's own.
     *
     * <p>They are honest statements about this build: the durable scheduling work is emitted, but no Runtime
     * delivers it and no Provider executes it, so a caller must acknowledge that explicitly rather than
     * inherit a claim the implementation cannot support.
     */
    public static final List<String> FULL_RISK_CODES = List.of(
            "RUNTIME_UNAVAILABLE",
            "SCHEDULE_WORK_DEFERRED");

    /**
     * The bounded machine topic a scheduling-registration outbox event is addressed to.
     *
     * <p>It names the delivery contract only: T21 emits the event and never delivers it, so no queue,
     * dispatcher, lease or worker is created here.
     */
    public static final String OUTBOX_TOPIC = "campaign.schedule.registration";

    /** The largest number of outbox events one in-memory store keeps, so a local store stays bounded. */
    public static final int MAX_OUTBOX_EVENTS = 4096;

    /** The bounded owner scope of a scheduling-registration event: a Campaign's active schedule only. */
    public static final String OUTBOX_SCOPE = "CAMPAIGN_SCHEDULE";

    private FullPublishVersions() {
    }
}