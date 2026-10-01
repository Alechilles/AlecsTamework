package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.ActivityDomain;
import com.alechilles.alecstamework.api.ActivityFilter;
import com.alechilles.alecstamework.api.ActivityHeader;
import com.alechilles.alecstamework.api.ActivityIds;
import com.alechilles.alecstamework.api.CommandLinkView;
import com.alechilles.alecstamework.api.TameActivityView;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import com.alechilles.alecstamework.api.Vector3View;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.damage.SimpleClaimsTamedDamagePolicy;
import com.alechilles.alecstamework.items.capturepolicy.CapturePolicyRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexTameworkApiTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final String TOOL = "00000000-0000-0000-0000-0000000000c0";

    private final CompanionIndex index = new CompanionIndex(() -> 1_000L, (before, after) -> { });

    private IndexTameworkApi api() {
        IndexNpcProfilesApi profiles = new IndexNpcProfilesApi(
                new CompanionQueries(index, new LoadedBodies<>()), id -> null);
        return new IndexTameworkApi(
                profiles,
                new TameworkEventBus(null),
                null,
                new InteractionExtensionRegistry(null),
                new TraitEffectRegistry(null, profiles),
                new SimpleClaimsTamedDamagePolicy(),
                new CommandUiRegistry(),
                new CommandHudRegistry(),
                new ItemFeatureRegistry(),
                new CapturePolicyRegistry());
    }

    @Test
    void anUnloadedCompanionReportsItsToolsAndRecordPositionButNoHome() {
        CompanionRecord record = CompanionRecord.builder(
                        UUID.randomUUID(), "Sheep", CompanionLocation.live("default", 10.5, 64, -3))
                .ownerUuid(OWNER).toolIds(List.of(TOOL)).build();
        index.insert(record);
        String profileId = record.profileId().toString();

        try (IndexTameworkApi api = api()) {
            CommandLinkView link = api.commandLinks().getByProfileId(profileId).orElseThrow();

            assertEquals(Set.of(TOOL), link.toolIds());
            assertEquals(new Vector3View(10.5, 64, -3), link.lastKnownPosition());
            assertFalse(link.hasHomePosition());
            assertTrue(api.commandLinks().getHomePosition(profileId).isEmpty());
            assertTrue(api.policies().isOwner(profileId, OWNER));
        }
    }

    @Test
    void anActivityFromTheRuntimePublisherReachesAnApiSubscriberUntilTheApiCloses() {
        IndexTameworkApi api = api();
        List<String> seen = new ArrayList<>();
        api.activities().subscribe("consumer", ActivityFilter.forDomain(ActivityDomain.TAMING),
                activity -> seen.add(activity.header().actionId()));
        LiveActivityFeed.Publisher publisher = api.activityPublisher();

        publisher.publish(tame());
        assertEquals(List.of(ActivityIds.TAME_SUCCESS), seen);
        assertTrue(api.getCapabilities().contains(TameworkApiCapability.ACTIVITY_FEED_V2));

        api.close();
        publisher.publish(tame());

        assertEquals(1, seen.size());
        assertFalse(api.getCapabilities().contains(TameworkApiCapability.ACTIVITY_FEED_V2));
        assertFalse(api.activities().status("consumer").available());
    }

    private static TameActivityView tame() {
        return new TameActivityView(
                new ActivityHeader(UUID.randomUUID(), 0L, ActivityIds.TAME_SUCCESS, Instant.EPOCH),
                "runeteria:husbandry",
                Set.of("family:cow"),
                "role:cow",
                OWNER,
                UUID.randomUUID(),
                "runeteria:husbandry/tame_success");
    }
}
