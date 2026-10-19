package com.company.campaign.segment.reference;

import com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.company.campaign.segment.reference.testsupport.SegmentReferenceFixtures.ref;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Retention holds and deletion: a referenced immutable version survives for exactly as long as a published
 * dependent version holds it.
 */
class AudienceRetentionPolicyTest {

    private static final Instant HELD_AT = Instant.parse("2026-10-01T00:00:00Z");

    private static AudienceRetentionHold hold(String holdId, AudienceVersionRef reference) {
        return new AudienceRetentionHold(holdId, reference, "audience-dependent", "version-1", HELD_AT);
    }

    @Test
    void aHeldVersionSurvivesHardDeletion() {
        AudienceVersionRef referenced = ref("audience-vip", "version-7");
        InMemoryAudienceRetentionStore store = InMemoryAudienceRetentionStore.deletableWhenUnheld();
        store.retain(referenced);
        store.acquire(hold("hold-1", referenced));

        assertThat(store.hardDelete(referenced)).isEqualTo(HardDeletionOutcome.REJECTED_ACTIVE_HOLD);
        assertThat(store.isRetained(referenced)).isTrue();
        assertThat(store.isDeleted(referenced)).isFalse();
        assertThat(store.activeHolds(referenced)).hasSize(1);
    }

    @Test
    void deletionSucceedsOnlyAfterEveryHoldIsReleasedUnderAPolicyThatPermitsIt() {
        AudienceVersionRef referenced = ref("audience-vip", "version-7");
        InMemoryAudienceRetentionStore store = InMemoryAudienceRetentionStore.deletableWhenUnheld();
        store.retain(referenced);
        store.acquire(hold("hold-1", referenced));
        store.acquire(hold("hold-2", referenced));

        store.release("hold-1");
        assertThat(store.hardDelete(referenced)).isEqualTo(HardDeletionOutcome.REJECTED_ACTIVE_HOLD);

        store.release("hold-2");
        assertThat(store.activeHolds(referenced)).isEmpty();
        // Releasing the final hold only makes the version eligible; the deletion is still explicit.
        assertThat(store.isRetained(referenced)).isTrue();
        assertThat(store.hardDelete(referenced)).isEqualTo(HardDeletionOutcome.DELETED);
        assertThat(store.isDeleted(referenced)).isTrue();
    }

    @Test
    void aRetainIndefinitelyPolicyKeepsAnUnheldVersionAlive() {
        AudienceVersionRef referenced = ref("audience-vip", "version-7");
        InMemoryAudienceRetentionStore store = InMemoryAudienceRetentionStore.retaining();
        store.retain(referenced);
        store.acquire(hold("hold-1", referenced));
        store.release("hold-1");

        assertThat(store.releasePolicy()).isEqualTo(RetentionReleasePolicy.RETAIN_INDEFINITELY);
        assertThat(store.hardDelete(referenced)).isEqualTo(HardDeletionOutcome.RETAINED_BY_POLICY);
        assertThat(store.isRetained(referenced)).isTrue();
    }

    @Test
    void acquiringASameHoldIdentityTwiceIsIdempotentAndCannotRetargetIt() {
        AudienceVersionRef referenced = ref("audience-vip", "version-7");
        AudienceVersionRef other = ref("audience-other", "version-1");
        InMemoryAudienceRetentionStore store = InMemoryAudienceRetentionStore.deletableWhenUnheld();

        store.acquire(hold("hold-1", referenced));
        store.acquire(hold("hold-1", referenced));

        assertThat(store.activeHolds(referenced)).hasSize(1);
        assertThatThrownBy(() -> store.acquire(hold("hold-1", other)))
                .isInstanceOf(ReferenceException.class);
    }

    @Test
    void releasingAnUnknownHoldIsANoOpAndAnUnknownTargetHasItsOwnOutcome() {
        AudienceVersionRef referenced = ref("audience-vip", "version-7");
        AudienceVersionRef unknown = ref("audience-unknown", "version-1");
        InMemoryAudienceRetentionStore store = InMemoryAudienceRetentionStore.deletableWhenUnheld();

        store.release("never-existed");
        store.release(null);

        assertThat(store.hardDelete(unknown)).isEqualTo(HardDeletionOutcome.UNKNOWN_TARGET);
        assertThat(store.hardDelete(referenced)).isEqualTo(HardDeletionOutcome.UNKNOWN_TARGET);
        assertThatThrownBy(() -> store.hardDelete(null)).isInstanceOf(ReferenceException.class);
    }

    @Test
    void aHoldCannotBeTakenOnADeletedVersion() {
        AudienceVersionRef referenced = ref("audience-vip", "version-7");
        InMemoryAudienceRetentionStore store = InMemoryAudienceRetentionStore.deletableWhenUnheld();
        store.retain(referenced);
        assertThat(store.hardDelete(referenced)).isEqualTo(HardDeletionOutcome.DELETED);

        assertThatThrownBy(() -> store.acquire(hold("hold-1", referenced)))
                .isInstanceOf(ReferenceException.class)
                .hasFieldOrPropertyWithValue("code", ReferenceErrorCodes.RETENTION_TARGET_UNKNOWN);
    }

    @Test
    void activeHoldsAreOrderedByIdentityAndAStoreRequiresAPolicy() {
        AudienceVersionRef referenced = ref("audience-vip", "version-7");
        InMemoryAudienceRetentionStore store = InMemoryAudienceRetentionStore.deletableWhenUnheld();
        store.acquire(hold("hold-b", referenced));
        store.acquire(hold("hold-a", referenced));

        assertThat(store.activeHolds(referenced).stream().map(AudienceRetentionHold::holdId))
                .containsExactly("hold-a", "hold-b");
        assertThat(store.activeHolds(null)).isEmpty();

        assertThatThrownBy(() -> new InMemoryAudienceRetentionStore(null))
                .isInstanceOf(ReferenceException.class);
    }

    @Test
    void aHoldCarriesTheExactPinAndRequiresEveryIdentity() {
        AudienceVersionRef referenced = ref("audience-vip", "version-7");
        assertThat(hold("hold-1", referenced).heldReference()).isEqualTo(referenced);

        assertThatThrownBy(() -> new AudienceRetentionHold(null, referenced, "audience-dependent", "version-1",
                HELD_AT)).isInstanceOf(ReferenceException.class);
        assertThatThrownBy(() -> new AudienceRetentionHold("hold-1", null, "audience-dependent", "version-1",
                HELD_AT)).isInstanceOf(ReferenceException.class);
        assertThatThrownBy(() -> new AudienceRetentionHold("hold-1", referenced, "audience-dependent", "version-1",
                null)).isInstanceOf(ReferenceException.class);
    }
}