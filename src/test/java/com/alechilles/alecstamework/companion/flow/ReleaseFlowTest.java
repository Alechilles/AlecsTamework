package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseFlowTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private static CompanionRecord live(UUID profile) {
        return CompanionTransitions.newLive(profile, 0, new CompanionTransitions.BodyFacts(UUID.randomUUID(), OWNER,
                "Alec", "Tamed_Sheep", null, "default", 0, 0, 0, List.of(), CompanionSummary.EMPTY));
    }

    @Test
    void releaseTombstonesTheRecordAndQueuesOneSnapshotDelete() {
        CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (b, a) -> { });
        UUID profile = UUID.randomUUID();
        index.insert(live(profile));
        List<UUID> deleted = new ArrayList<>();
        ReleaseFlow flow = new ReleaseFlow(index, new LoadedBodies<>(), deleted::add);

        assertEquals(ReleaseFlow.Result.RELEASED, flow.release(profile, OWNER).result());

        assertEquals(LocationKind.RELEASED, index.get(profile).location().kind());
        assertEquals(List.of(profile), deleted);
    }

    @Test
    void aRefusedReleaseQueuesNoSnapshotDelete() {
        CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (b, a) -> { });
        UUID profile = UUID.randomUUID();
        index.insert(live(profile));
        List<UUID> deleted = new ArrayList<>();
        ReleaseFlow flow = new ReleaseFlow(index, new LoadedBodies<>(), deleted::add);
        flow.release(profile, OWNER);
        deleted.clear();

        assertEquals(ReleaseFlow.Result.NOT_OWNER, flow.release(profile, UUID.randomUUID()).result());
        assertEquals(ReleaseFlow.Result.NOT_RELEASABLE, flow.release(profile, OWNER).result());

        assertTrue(deleted.isEmpty());
    }

    @Test
    void onlyTheOwnerMayRelease() {
        CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (b, a) -> { });
        UUID profile = UUID.randomUUID();
        index.insert(live(profile));
        ReleaseFlow flow = new ReleaseFlow(index, new LoadedBodies<>(), id -> { });

        assertEquals(ReleaseFlow.Result.NOT_OWNER, flow.release(profile, UUID.randomUUID()).result());
        assertEquals(LocationKind.LIVE, index.get(profile).location().kind());
    }
}
