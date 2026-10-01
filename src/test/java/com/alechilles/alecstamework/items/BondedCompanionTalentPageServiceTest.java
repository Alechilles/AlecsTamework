package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.BondedCompanionAvailability;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.ui.BondedCompanionPanelPresentation;
import com.alechilles.alecstamework.ui.BondedCompanionStatusPresentation;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/** A talent change that the bonded API finishes after the click. */
class BondedCompanionTalentPageServiceTest {
    private static final UUID OWNER = UUID.fromString("75000000-0000-0000-0000-000000000001");
    private static final String PROFILE = "75000000-0000-0000-0000-000000000002";

    /** Without the late result the page kept the old points and the player was never told. */
    @Test
    void aLateResultUpdatesThePageStateAndIsAnnouncedOnTheOwnersWorldThread() throws Exception {
        CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>> pending = new CompletableFuture<>();
        BondedCompanionApi api = (BondedCompanionApi) Proxy.newProxyInstance(
                BondedCompanionApi.class.getClassLoader(), new Class<?>[] {BondedCompanionApi.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "availability" -> BondedCompanionAvailability.availableNow();
                    case "updateTalents" -> pending;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Player player = (Player) unsafe().allocateInstance(Player.class);
        player.setLegacyUUID(OWNER);
        List<UUID> dispatched = new ArrayList<>();
        try (TestEntityComponentStore store = new TestEntityComponentStore(new TestEntityStore())) {
            Ref<EntityStore> ref = store.createReference();
            BondedCompanionTalentPageService service = new BondedCompanionTalentPageService(
                    () -> api, new CommandFeedbackService(null),
                    (owner, operation) -> {
                        dispatched.add(owner);
                        operation.run(ref, store);
                        return true;
                    },
                    (currentRef, currentStore) -> player);
            BondedCompanionTalentPageService.ManagedTarget target = service.managedTarget(player, activeRow());
            assertEquals(4, service.managedSnapshot(player, target).availablePoints());

            BondedCompanionTalentPageService.ManagedMutation clicked = service.purchaseManaged(player, target, "swift");

            assertTrue(clicked.pending());
            assertFalse(clicked.applied());
            assertTrue(dispatched.isEmpty());

            pending.complete(new BondedCompanionResult<>(BondedCompanionResultCode.SUCCESS, view(), null));

            assertEquals(List.of(OWNER), dispatched);
            assertEquals(3, service.managedSnapshot(player, target).availablePoints());
        }
    }

    /** Level 5 with nothing spent: four points to spend. */
    private static BondedCompanionPanelPresentation activeRow() {
        return new BondedCompanionPanelPresentation(PROFILE, "hydragon:dragons", "Bonded_Miniwyvern_Storm", 4L,
                "Nimbus", "Miniwyvern", null, null, Map.of("level", "5"), Map.of(),
                new BondedCompanionStatusPresentation(BondedCompanionStateView.ACTIVE,
                        BondedCompanionStatusPresentation.Action.DISMISS, true, null, 0L), null);
    }

    /** The view the API returns once "swift" (1 point) is bought. */
    private static BondedCompanionProfileView view() {
        return new BondedCompanionProfileView(PROFILE, OWNER, "hydragon:dragons", "dragons",
                "Bonded_Miniwyvern_Storm", "Nimbus", "Miniwyvern", null, 4L, BondedCompanionStateView.STORED,
                true, false, false, Map.of("talents", "swift", "talentSpentPoints", "1", "level", "5"),
                null, 0L, null);
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static final class TestEntityStore extends EntityStore {
        private TestEntityStore() {
            super(null);
        }
    }
}
