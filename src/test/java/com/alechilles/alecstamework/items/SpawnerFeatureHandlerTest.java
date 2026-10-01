package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.item.CaptureItemOwnership.Release;
import java.util.UUID;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void aProviderKeyWithNoTranslationIsShownAsTheBuiltInDenialNotAsTheKey() {
        String shown = SpawnerFeatureHandler.populationText("en-US", "othermod.husbandry.denied.levelTooLow");

        assertEquals("You do not meet the Husbandry requirements for this companion.", shown);
        // A key that resolves is shown as its own text.
        assertEquals("Your owned companion limit has been reached.",
                SpawnerFeatureHandler.populationText("en-US", "tamework.ui.population.ownedLimit"));
        assertFalse(SpawnerFeatureHandler.populationText("en-US", null).isBlank());
    }

    @Test
    void aBondedCaptureStoresTheBodyTamedAndInTheRoleItsRosterFamilyAllows() {
        BsonDocument npc = new BsonDocument("RoleName", new BsonString("NordicDrake"))
                .append("ActiveMC", new BsonString("Fly"));
        BsonDocument entity = new BsonDocument("Components", new BsonDocument("NPC", npc));

        BsonDocument stored = SpawnerFeatureHandler.entityPatch(true, "Tamed_NordicDrake").apply(entity);

        BsonDocument components = stored.getDocument("Components");
        assertEquals("Tamed_NordicDrake", components.getDocument("NPC").getString("RoleName").getValue());
        // The wild role's motion controller is not carried into the tamed role.
        assertFalse(components.getDocument("NPC").containsKey("ActiveMC"));
        assertEquals(com.alechilles.alecstamework.companion.flow.SnapshotPatch.withTamed(entity).getDocument("Components").get("TameworkTamed"),
                components.get("TameworkTamed"));
        // The body in the world is not changed by the capture's snapshot patch.
        assertEquals("NordicDrake", npc.getString("RoleName").getValue());
        assertNull(SpawnerFeatureHandler.entityPatch(false, null));
    }
}
