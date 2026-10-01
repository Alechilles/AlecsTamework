package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.item.CaptureItemOwnership.Release;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SpawnerFeatureHandlerTest {

    @Test
    void aReleaseAsksTheRestoreForTheOwnerItsOwnershipDecisionNames() {
        UUID releaser = UUID.randomUUID();

        assertEquals(new RestoreFlow.Owner(releaser, "Releaser"),
                SpawnerFeatureHandler.releaseOwner(Release.ASSIGN_RELEASER, releaser, "Releaser"));
        // The owner's own release changes nothing; an unowned wild capture comes back unowned.
        assertNull(SpawnerFeatureHandler.releaseOwner(Release.KEEP_OWNER, releaser, "Releaser"));
        assertEquals(new RestoreFlow.Owner(null, null),
                SpawnerFeatureHandler.releaseOwner(Release.UNOWNED, releaser, "Releaser"));
    }
}
