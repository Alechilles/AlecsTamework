package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.flow.CaptureFlow;
import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.item.CaptureItemOwnership.Release;
import com.alechilles.alecstamework.localization.LocalizedText;
import java.util.UUID;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertEquals(LocalizedText.resolve("en-US", CompanionAdmission.PROVIDER_DENIED_MESSAGE_KEY), shown);
        assertFalse(shown.equals(CompanionAdmission.PROVIDER_DENIED_MESSAGE_KEY), "the fallback itself resolves");
        // A key that resolves is shown as its own text.
        assertEquals(LocalizedText.resolve("en-US", CompanionAdmission.OWNED_LIMIT_MESSAGE_KEY),
                SpawnerFeatureHandler.populationText("en-US", CompanionAdmission.OWNED_LIMIT_MESSAGE_KEY));
        assertFalse(SpawnerFeatureHandler.populationText("en-US", null).isBlank());
    }

    @Test
    void aBondedCaptureStoresTheBodyTamedAndInTheRoleItsRosterFamilyAllows() {
        BsonDocument npc = new BsonDocument("RoleName", new BsonString("NordicDrake"))
                .append("ActiveMC", new BsonString("Fly"))
                .append("SpawnName", new BsonString("NordicDrake"))
                .append("Env", new BsonString("Zone3_Mountains"));
        BsonDocument entity = new BsonDocument("Components", new BsonDocument("NPC", npc));

        BsonDocument stored = SpawnerFeatureHandler.entityPatch(true, "Tamed_NordicDrake").apply(entity);

        BsonDocument components = stored.getDocument("Components");
        assertEquals("Tamed_NordicDrake", components.getDocument("NPC").getString("RoleName").getValue());
        // The wild role's motion controller is not carried into the tamed role.
        assertFalse(components.getDocument("NPC").containsKey("ActiveMC"));
        // Nor its spawn role and environment: the stored companion is detached from its spawning.
        assertFalse(components.getDocument("NPC").containsKey("SpawnName"));
        assertFalse(components.getDocument("NPC").containsKey("Env"));
        assertEquals(com.alechilles.alecstamework.companion.flow.SnapshotPatch.withTamed(entity).getDocument("Components").get("TameworkTamed"),
                components.get("TameworkTamed"));
        // The body in the world is not changed by the capture's snapshot patch.
        assertEquals("NordicDrake", npc.getString("RoleName").getValue());
        assertNull(SpawnerFeatureHandler.entityPatch(false, null));
    }

    @Test
    void aCaptureThatPaidBeforeItsCommitIsRefundedUnlessTheCompanionWasCaptured() {
        for (CaptureFlow.Result result : CaptureFlow.Result.values()) {
            assertEquals(result != CaptureFlow.Result.CAPTURED,
                    SpawnerFeatureHandler.refundsSource(new CaptureFlow.Outcome(result, null)), result.name());
        }
        // A commit that failed with no outcome at all changed nothing either.
        assertTrue(SpawnerFeatureHandler.refundsSource(null));
    }
}
