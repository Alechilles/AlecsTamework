package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.ActivityDomain;
import com.alechilles.alecstamework.api.ActivityFeedSubscription;
import com.alechilles.alecstamework.api.ActivityFilter;
import com.alechilles.alecstamework.api.ActivityHeader;
import com.alechilles.alecstamework.api.ActivityIds;
import com.alechilles.alecstamework.api.ManagedActivityView;
import com.alechilles.alecstamework.api.TameActivityView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior checks for the filtered Activity API V2 feed. */
class LiveActivityFeedTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID COMPANION = UUID.fromString(
            "20000000-0000-0000-0000-000000000001");

    @Test
    void deliversOnlyMatchingDomainsAndExactActions() {
        LiveActivityFeed feed = new LiveActivityFeed();
        List<String> managed = new ArrayList<>();
        List<String> harvestOnly = new ArrayList<>();
        List<String> taming = new ArrayList<>();

        feed.subscribe(
                "managed",
                new ActivityFilter(Set.of(ActivityDomain.MANAGED_CARE), Set.of()),
                activity -> managed.add(activity.header().actionId())
        );
        feed.subscribe(
                "harvest",
                new ActivityFilter(
                        Set.of(ActivityDomain.MANAGED_CARE),
                        Set.of(ActivityIds.HARVEST)
                ),
                activity -> harvestOnly.add(activity.header().actionId())
        );
        feed.subscribe(
                "taming",
                new ActivityFilter(Set.of(ActivityDomain.TAMING), Set.of()),
                activity -> taming.add(activity.header().actionId())
        );

        feed.publish(managedActivity(ActivityIds.FEED));
        feed.publish(managedActivity(ActivityIds.HARVEST));
        feed.publish(tameActivity());

        assertEquals(List.of(ActivityIds.FEED, ActivityIds.HARVEST), managed);
        assertEquals(List.of(ActivityIds.HARVEST), harvestOnly);
        assertEquals(List.of(ActivityIds.TAME_SUCCESS), taming);
        feed.close();
    }

    @Test
    void rejectsDuplicateConsumerIdsAndAllowsReuseAfterUnsubscribe() {
        LiveActivityFeed feed = new LiveActivityFeed();
        ActivityFilter filter = new ActivityFilter(
                Set.of(ActivityDomain.MANAGED_CARE), Set.of());
        ActivityFeedSubscription first = feed.subscribe(
                "husbandry", filter, ignored -> { });

        assertThrows(
                IllegalStateException.class,
                () -> feed.subscribe(" husbandry ", filter, ignored -> { })
        );

        first.close();
        assertDoesNotThrow(() -> feed.subscribe("husbandry", filter, ignored -> { }));
        feed.close();
    }

    @Test
    void isolatesCallbackExceptionsAndTracksLastAttemptedSequencePerConsumer() {
        LiveActivityFeed feed = new LiveActivityFeed();
        AtomicInteger healthyCalls = new AtomicInteger();
        feed.subscribe(
                "throws",
                new ActivityFilter(Set.of(ActivityDomain.MANAGED_CARE), Set.of()),
                ignored -> { throw new IllegalStateException("consumer failure"); }
        );
        feed.subscribe(
                "healthy",
                new ActivityFilter(Set.of(ActivityDomain.MANAGED_CARE), Set.of()),
                ignored -> healthyCalls.incrementAndGet()
        );

        assertDoesNotThrow(() -> {
            feed.publish(managedActivity(ActivityIds.FEED));
            feed.publish(managedActivity(ActivityIds.HARVEST));
        });

        assertEquals(2, healthyCalls.get());
        assertEquals(2L, feed.status("throws").lastAttemptedSequence());
        assertEquals(2L, feed.status("healthy").lastAttemptedSequence());
        feed.close();
    }

    @Test
    void changesInterestOnSubscriptionAndCloseWithoutConstructingAnActivity() {
        LiveActivityFeed feed = new LiveActivityFeed();

        assertFalse(feed.hasInterest(ActivityDomain.MANAGED_CARE, ActivityIds.FEED));
        ActivityFeedSubscription subscription = feed.subscribe(
                "husbandry",
                new ActivityFilter(
                        Set.of(ActivityDomain.MANAGED_CARE),
                        Set.of(ActivityIds.FEED)
                ),
                ignored -> { }
        );
        assertTrue(feed.hasInterest(ActivityDomain.MANAGED_CARE, ActivityIds.FEED));
        assertFalse(feed.hasInterest(ActivityDomain.MANAGED_CARE, ActivityIds.HARVEST));

        subscription.close();
        assertFalse(feed.hasInterest(ActivityDomain.MANAGED_CARE, ActivityIds.FEED));
        feed.close();
    }

    @Test
    void closesSubscriptionsAndStopsDelivery() {
        LiveActivityFeed feed = new LiveActivityFeed();
        AtomicInteger calls = new AtomicInteger();
        ActivityFeedSubscription subscription = feed.subscribe(
                "husbandry",
                new ActivityFilter(Set.of(ActivityDomain.MANAGED_CARE), Set.of()),
                ignored -> calls.incrementAndGet()
        );

        feed.publish(managedActivity(ActivityIds.FEED));
        subscription.close();
        subscription.close();
        feed.publish(managedActivity(ActivityIds.FEED));

        assertEquals(1, calls.get());
        assertFalse(feed.status("husbandry").subscribed());
        feed.close();
    }

    private static ManagedActivityView managedActivity(String actionId) {
        return new ManagedActivityView(
                header(actionId),
                "runeteria:husbandry",
                Set.of("family:cow"),
                "role:cow",
                OWNER,
                COMPANION,
                "runeteria:husbandry/feed"
        );
    }

    private static TameActivityView tameActivity() {
        return new TameActivityView(
                header(ActivityIds.TAME_SUCCESS),
                "runeteria:husbandry",
                Set.of("family:cow"),
                "role:cow",
                OWNER,
                COMPANION,
                "runeteria:husbandry/tame_success"
        );
    }

    private static ActivityHeader header(String actionId) {
        return new ActivityHeader(UUID.randomUUID(), 0L, actionId, Instant.EPOCH);
    }
}
