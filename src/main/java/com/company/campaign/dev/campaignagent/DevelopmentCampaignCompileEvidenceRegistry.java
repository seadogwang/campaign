package com.company.campaign.dev.campaignagent;

import com.company.campaign.builder.campaignagent.CampaignAgentErrorCodes;
import com.company.campaign.builder.campaignagent.CampaignAgentException;
import com.company.campaign.builder.campaignagent.CampaignCompileEvidence;
import com.company.campaign.builder.campaignagent.CampaignCompileEvidenceRegistry;
import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.compiler.CapabilityCompileContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A bounded, host-owned, in-memory implementation of {@link CampaignCompileEvidenceRegistry} for the development host.
 *
 * <p>The registry holds at most {@link #MAX_ENTRIES} total entries across both its staged and bound maps,
 * and evicts the oldest entry when full. A stale entry (older than {@link #MAX_AGE}) is evicted on lookup
 * or bind. The two-stage contract is:
 *
 * <ol>
 *   <li>{@code stage} overwrites any prior staged entry for the same conversationId+runId pair.</li>
 *   <li>{@code bind} moves the staged entry to the bound map under the proposal hash; it fails closed
 *       when no staged entry exists for the given pair.</li>
 *   <li>{@code lookup} finds bound entries by proposal hash, evicting stale ones.</li>
 * </ol>
 */
public final class DevelopmentCampaignCompileEvidenceRegistry implements CampaignCompileEvidenceRegistry {
    static final int MAX_ENTRIES = 64;
    static final Duration MAX_AGE = Duration.ofHours(2);
    private final Object lock = new Object();
    private final Clock clock;

    private final Map<StagingKey, Entry> staged = new LinkedHashMap<>();
    private final Map<String, Entry> bound = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return totalSize() > MAX_ENTRIES;
        }
    };

    public DevelopmentCampaignCompileEvidenceRegistry(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void stage(String conversationId, String runId,
            CapabilityCompileContext context, T19PackIndex packIndex) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(packIndex, "packIndex");
        StagingKey key = new StagingKey(conversationId, runId);
        synchronized (lock) {
            // Overwrite any prior staged entry for this conversation+run pair.
            staged.put(key, new Entry(new CampaignCompileEvidence(context, packIndex), clock.instant()));
            evictStaleStaged();
            enforceMaxEntries();
        }
    }

    @Override
    public void bind(String conversationId, String runId, String proposalHash) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(proposalHash, "proposalHash");
        StagingKey key = new StagingKey(conversationId, runId);
        synchronized (lock) {
            Entry entry = staged.remove(key);
            if (entry == null) {
                throw CampaignAgentException.of(
                        CampaignAgentErrorCodes.ARTIFACT_REFUSED,
                        "No staged Segment compile evidence exists for conversation "
                                + conversationId + " run " + runId + ".");
            }
            if (isStale(entry)) {
                throw CampaignAgentException.of(
                        CampaignAgentErrorCodes.ARTIFACT_REFUSED,
                        "The staged Segment compile evidence for conversation "
                                + conversationId + " run " + runId + " is stale.");
            }
            bound.put(proposalHash, entry);
        }
    }

    @Override
    public Optional<CampaignCompileEvidence> lookup(String proposalHash) {
        Objects.requireNonNull(proposalHash, "proposalHash");
        synchronized (lock) {
            Entry entry = bound.get(proposalHash);
            if (entry == null) {
                return Optional.empty();
            }
            if (isStale(entry)) {
                bound.remove(proposalHash);
                return Optional.empty();
            }
            return Optional.of(entry.evidence);
        }
    }

    @Override
    public void remove(String proposalHash) {
        Objects.requireNonNull(proposalHash, "proposalHash");
        synchronized (lock) {
            bound.remove(proposalHash);
        }
    }

    /** Visible for diagnostics only; does not evict. */
    int stagedSize() {
        synchronized (lock) {
            return staged.size();
        }
    }

    /** Visible for diagnostics only; does not evict. */
    int boundSize() {
        synchronized (lock) {
            return bound.size();
        }
    }

    int size() {
        synchronized (lock) {
            return staged.size() + bound.size();
        }
    }

    private int totalSize() {
        return staged.size() + bound.size();
    }

    private void evictStaleStaged() {
        staged.entrySet().removeIf(e -> isStale(e.getValue()));
    }

    private void enforceMaxEntries() {
        while (totalSize() > MAX_ENTRIES) {
            // Evict the oldest staged entry first; if none, evict the oldest bound.
            if (!staged.isEmpty()) {
                staged.remove(staged.keySet().iterator().next());
            } else if (!bound.isEmpty()) {
                bound.remove(bound.keySet().iterator().next());
            } else {
                break;
            }
        }
    }

    private boolean isStale(Entry entry) {
        return Duration.between(entry.registeredAt, clock.instant()).compareTo(MAX_AGE) > 0;
    }

    private record StagingKey(String conversationId, String runId) {
        StagingKey {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(runId, "runId");
        }
    }

    private record Entry(CampaignCompileEvidence evidence, Instant registeredAt) {
        Entry {
            Objects.requireNonNull(evidence, "evidence");
            Objects.requireNonNull(registeredAt, "registeredAt");
        }
    }
}
