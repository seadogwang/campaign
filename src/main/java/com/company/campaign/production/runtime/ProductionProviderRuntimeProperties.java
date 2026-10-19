package com.company.campaign.production.runtime;

import com.company.campaign.action.ActionVersions;
import com.company.campaign.runtime.RuntimeFactRequest;
import com.company.campaign.runtime.RuntimeVersions;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The host-supplied, default-off configuration of the production provider runtime host.
 *
 * <p>Nothing here has a default that could accidentally start a worker or a thread: {@link #isEnabled()} and
 * {@link #isSchedulerEnabled()} are both {@code false} unless the host opts in. Only bounded operational limits
 * live here, and every one of them defaults to a conservative value inside the existing frozen domain bounds
 * ({@link RuntimeVersions}, {@link ActionVersions}, {@link RuntimeFactRequest}). No endpoint, credential, URL,
 * schema name, tenant, member or model value is committed.
 *
 * <p>{@link #validate()} refuses an enabled configuration whose value is missing or outside its frozen bound
 * before any worker, executor or thread exists. {@link #configurationHash()} is a one-way canonical hash
 * published as bounded evidence that discloses none of the values, and {@link #workerId()} is the stable,
 * server-generated worker identity derived from it.
 */
@ConfigurationProperties(prefix = "campaign.production.provider-runtime")
public class ProductionProviderRuntimeProperties {

    /** The largest number of tenants one cycle may scan. */
    public static final int MAX_TENANTS = 1_024;

    /** The largest number of Runtime or Action items one cycle may process per tenant. */
    public static final int MAX_ITEMS_PER_CYCLE = RuntimeVersions.MAX_CLAIM_CANDIDATES;

    /** The largest accepted scheduler cadence. */
    public static final Duration MAX_SCHEDULER_CADENCE = Duration.ofHours(1);

    /** The smallest accepted scheduler cadence. */
    public static final Duration MIN_SCHEDULER_CADENCE = Duration.ofSeconds(1);

    /** The stable prefix of every derived worker identity; never a random or host-supplied value. */
    public static final String WORKER_ID_PREFIX = "prw-";

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private boolean enabled = false;
    private boolean schedulerEnabled = false;
    private Duration leaseDuration = Duration.ofSeconds(30);
    private long factTimeoutMillis = 500L;
    private Duration materializationFreshness = Duration.ofHours(1);
    private int claimScanLimit = 256;
    private int ingestLimit = 256;
    private int runtimeAttempts = 3;
    private List<Duration> runtimeBackoff = new ArrayList<>(List.of(
            Duration.ofSeconds(5), Duration.ofSeconds(30)));
    private int submissionAttempts = 3;
    private List<Duration> submissionBackoff = new ArrayList<>(List.of(
            Duration.ofSeconds(5), Duration.ofSeconds(30)));
    private int verificationAttempts = 3;
    private List<Duration> verificationBackoff = new ArrayList<>(List.of(
            Duration.ofSeconds(5), Duration.ofSeconds(30)));
    private Duration schedulerCadence = Duration.ofSeconds(30);
    private int maxTenants = 32;
    private int maxRuntimeItemsPerCycle = 64;
    private int maxActionItemsPerCycle = 64;

    /** True only when the host explicitly opted into the production provider runtime host. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** True only when the host additionally opted into background scheduling. */
    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public long getFactTimeoutMillis() {
        return factTimeoutMillis;
    }

    public void setFactTimeoutMillis(long factTimeoutMillis) {
        this.factTimeoutMillis = factTimeoutMillis;
    }

    public Duration getMaterializationFreshness() {
        return materializationFreshness;
    }

    public void setMaterializationFreshness(Duration materializationFreshness) {
        this.materializationFreshness = materializationFreshness;
    }

    public int getClaimScanLimit() {
        return claimScanLimit;
    }

    public void setClaimScanLimit(int claimScanLimit) {
        this.claimScanLimit = claimScanLimit;
    }

    public int getIngestLimit() {
        return ingestLimit;
    }

    public void setIngestLimit(int ingestLimit) {
        this.ingestLimit = ingestLimit;
    }

    public int getRuntimeAttempts() {
        return runtimeAttempts;
    }

    public void setRuntimeAttempts(int runtimeAttempts) {
        this.runtimeAttempts = runtimeAttempts;
    }

    public List<Duration> getRuntimeBackoff() {
        return runtimeBackoff;
    }

    public void setRuntimeBackoff(List<Duration> runtimeBackoff) {
        this.runtimeBackoff = runtimeBackoff == null ? new ArrayList<>() : runtimeBackoff;
    }

    public int getSubmissionAttempts() {
        return submissionAttempts;
    }

    public void setSubmissionAttempts(int submissionAttempts) {
        this.submissionAttempts = submissionAttempts;
    }

    public List<Duration> getSubmissionBackoff() {
        return submissionBackoff;
    }

    public void setSubmissionBackoff(List<Duration> submissionBackoff) {
        this.submissionBackoff = submissionBackoff == null ? new ArrayList<>() : submissionBackoff;
    }

    public int getVerificationAttempts() {
        return verificationAttempts;
    }

    public void setVerificationAttempts(int verificationAttempts) {
        this.verificationAttempts = verificationAttempts;
    }

    public List<Duration> getVerificationBackoff() {
        return verificationBackoff;
    }

    public void setVerificationBackoff(List<Duration> verificationBackoff) {
        this.verificationBackoff = verificationBackoff == null ? new ArrayList<>() : verificationBackoff;
    }

    public Duration getSchedulerCadence() {
        return schedulerCadence;
    }

    public void setSchedulerCadence(Duration schedulerCadence) {
        this.schedulerCadence = schedulerCadence;
    }

    public int getMaxTenants() {
        return maxTenants;
    }

    public void setMaxTenants(int maxTenants) {
        this.maxTenants = maxTenants;
    }

    public int getMaxRuntimeItemsPerCycle() {
        return maxRuntimeItemsPerCycle;
    }

    public void setMaxRuntimeItemsPerCycle(int maxRuntimeItemsPerCycle) {
        this.maxRuntimeItemsPerCycle = maxRuntimeItemsPerCycle;
    }

    public int getMaxActionItemsPerCycle() {
        return maxActionItemsPerCycle;
    }

    public void setMaxActionItemsPerCycle(int maxActionItemsPerCycle) {
        this.maxActionItemsPerCycle = maxActionItemsPerCycle;
    }

    /**
     * Validates the whole configuration, refusing an enabled host with a missing or out-of-bounds setting.
     *
     * <p>A disabled configuration is always valid and validates nothing, so the normal application never fails
     * because a host left the production runtime off. When it is enabled, every bound is enforced here — before
     * any durable store is used, any worker exists or any thread or executor is created.
     */
    public void validate() {
        if (!enabled) {
            return;
        }
        if (leaseDuration == null
                || leaseDuration.compareTo(RuntimeVersions.MIN_LEASE_DURATION) < 0
                || leaseDuration.compareTo(RuntimeVersions.MAX_LEASE_DURATION) > 0
                || leaseDuration.compareTo(ActionVersions.MAX_LEASE_DURATION) > 0) {
            throw invalid("leaseDuration");
        }
        if (factTimeoutMillis < 1 || factTimeoutMillis > RuntimeFactRequest.MAX_TIMEOUT_MILLIS) {
            throw invalid("factTimeoutMillis");
        }
        if (materializationFreshness == null
                || materializationFreshness.compareTo(RuntimeVersions.MIN_FRESHNESS_AGE) < 0
                || materializationFreshness.compareTo(RuntimeVersions.MAX_FRESHNESS_AGE) > 0) {
            throw invalid("materializationFreshness");
        }
        if (claimScanLimit < 1 || claimScanLimit > RuntimeVersions.MAX_CLAIM_CANDIDATES) {
            throw invalid("claimScanLimit");
        }
        if (ingestLimit < 1 || ingestLimit > ActionVersions.MAX_CLAIM_CANDIDATES) {
            throw invalid("ingestLimit");
        }
        requireSchedule(runtimeAttempts, runtimeBackoff, "runtimeBackoff");
        requireSchedule(submissionAttempts, submissionBackoff, "submissionBackoff");
        requireSchedule(verificationAttempts, verificationBackoff, "verificationBackoff");
        if (schedulerCadence == null
                || schedulerCadence.compareTo(MIN_SCHEDULER_CADENCE) < 0
                || schedulerCadence.compareTo(MAX_SCHEDULER_CADENCE) > 0) {
            throw invalid("schedulerCadence");
        }
        if (maxTenants < 1 || maxTenants > MAX_TENANTS) {
            throw invalid("maxTenants");
        }
        if (maxRuntimeItemsPerCycle < 1 || maxRuntimeItemsPerCycle > MAX_ITEMS_PER_CYCLE) {
            throw invalid("maxRuntimeItemsPerCycle");
        }
        if (maxActionItemsPerCycle < 1 || maxActionItemsPerCycle > MAX_ITEMS_PER_CYCLE) {
            throw invalid("maxActionItemsPerCycle");
        }
    }

    /**
     * The one-way canonical hash of the effective bounded configuration.
     *
     * <p>It is safe to publish: it discloses no URL, credential, tenant, member or provider value. Two hosts
     * that differ in any bound produce different hashes, and a host cannot recompute it from a partial value.
     */
    public ContentHash configurationHash() {
        return CANONICAL_JSON.hash(new Material(
                text(leaseDuration),
                factTimeoutMillis,
                text(materializationFreshness),
                claimScanLimit,
                ingestLimit,
                runtimeAttempts,
                text(runtimeBackoff),
                submissionAttempts,
                text(submissionBackoff),
                verificationAttempts,
                text(verificationBackoff),
                text(schedulerCadence),
                maxTenants,
                maxRuntimeItemsPerCycle,
                maxActionItemsPerCycle));
    }

    /**
     * The stable, server-generated worker identity of this configuration.
     *
     * <p>It is derived deterministically from {@link #configurationHash()}: it never reads a clock, a random
     * source, the host name or an environment variable, so two restarts of the same configuration use the same
     * bounded opaque identity and no value a caller supplies can choose it.
     */
    public String workerId() {
        String hash = configurationHash().value();
        return WORKER_ID_PREFIX + hash.substring(hash.indexOf(':') + 1, hash.indexOf(':') + 1 + 32);
    }

    private static void requireSchedule(int attempts, List<Duration> backoff, String field) {
        if (attempts < 1 || attempts > ActionVersions.MAX_POLICY_ATTEMPTS) {
            throw invalid(field);
        }
        if (backoff == null || backoff.size() != attempts - 1) {
            throw invalid(field);
        }
        for (Duration delay : backoff) {
            if (delay == null || delay.isZero() || delay.isNegative()
                    || delay.compareTo(ActionVersions.MAX_RETRY_BACKOFF) > 0) {
                throw invalid(field);
            }
        }
    }

    private static String text(Duration duration) {
        return duration == null ? null : duration.toString();
    }

    private static List<String> text(List<Duration> durations) {
        List<String> values = new ArrayList<>(durations.size());
        for (Duration duration : durations) {
            values.add(duration.toString());
        }
        return List.copyOf(values);
    }

    private static ProductionProviderRuntimeConfigurationException invalid(String field) {
        return new ProductionProviderRuntimeConfigurationException(
                ProductionProviderRuntimeErrorCodes.CONFIGURATION_INVALID,
                "The opt-in production provider runtime configuration is missing or out of bounds: " + field);
    }

    /** Redacted diagnostics: the bounds only, never a value that could be a secret. */
    @Override
    public String toString() {
        return "ProductionProviderRuntimeProperties[enabled=" + enabled
                + ", schedulerEnabled=" + schedulerEnabled
                + ", leaseDuration=" + leaseDuration
                + ", factTimeoutMillis=" + factTimeoutMillis
                + ", materializationFreshness=" + materializationFreshness
                + ", claimScanLimit=" + claimScanLimit
                + ", ingestLimit=" + ingestLimit
                + ", runtimeAttempts=" + runtimeAttempts
                + ", submissionAttempts=" + submissionAttempts
                + ", verificationAttempts=" + verificationAttempts
                + ", schedulerCadence=" + schedulerCadence
                + ", maxTenants=" + maxTenants
                + ", maxRuntimeItemsPerCycle=" + maxRuntimeItemsPerCycle
                + ", maxActionItemsPerCycle=" + maxActionItemsPerCycle + ']';
    }

    /** Canonical hash material; every duration is bound by its canonical ISO-8601 text, never by a rendering. */
    record Material(
            String leaseDurationText,
            long factTimeoutMillis,
            String materializationFreshnessText,
            int claimScanLimit,
            int ingestLimit,
            int runtimeAttempts,
            List<String> runtimeBackoffText,
            int submissionAttempts,
            List<String> submissionBackoffText,
            int verificationAttempts,
            List<String> verificationBackoffText,
            String schedulerCadenceText,
            int maxTenants,
            int maxRuntimeItemsPerCycle,
            int maxActionItemsPerCycle) {
    }
}