package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.persistence.FullStateSnapshotCodecAdapter;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.NonTicking;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.UIComponentsUpdate;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.entityui.UIComponentList;
import com.hypixel.hytale.server.core.modules.entityui.UIComponentSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for releasing capture projections back into entity tracking. */
class HytaleCaptureReleaseProjectionGatewayTest {

    /** Pre-fix durable release operations must also use paused needs state. */
    @Test
    void oldProjectionIsNormalizedBeforeRecoverySpawn() {
        SnapshotCodecRegistry codecs = new SnapshotCodecRegistry(List.of(
                new FullStateSnapshotCodecAdapter(
                        CompanionFullStateProjection.KIND,
                        CompanionFullStateProjection.VERSION
                )
        ));
        TameworkNeedsComponent oldNeeds = new TameworkNeedsComponent(
                "needs", 35.0D, 45.0D, 12.0D, 18.0D,
                1_000L, 2_000L
        );
        SnapshotCodecRegistry.EncodedSnapshot projection = codecs.encode(
                CompanionFullStateProjection.KIND,
                CompanionFullStateProjection.VERSION,
                CoopResidentStateSnapshot.class,
                new CoopResidentStateSnapshot(
                        UUID.randomUUID(), null, -1, "tamework_test",
                        null, null, null, null, null, oldNeeds,
                        null, null, null, null, null, null,
                        25.0D, 50.0D, 50.0D, 3_000L
                )
        );

        SnapshotDecodeResult.Decoded<CoopResidentStateSnapshot> decoded =
                (SnapshotDecodeResult.Decoded<CoopResidentStateSnapshot>)
                        HytaleCaptureReleaseProjectionGateway.decodeProjection(
                                codecs, projection
                        );
        TameworkNeedsComponent needs = decoded.value().needs();

        assertEquals(0.0D, needs.getPendingNeedsDamage());
        assertEquals(0L, needs.getLastUpdateMs());
        assertEquals(0L, needs.getLastPassiveSweepMs());
    }

    @Test
    void releaseRestartsNewlyVisibleUiSynchronization() throws Exception {
        ComponentRegistry<EntityStore> registry = new ComponentRegistry<>();
        ComponentType<EntityStore, EntityTrackerSystems.Visible> visibleType =
                registry.registerComponent(
                        EntityTrackerSystems.Visible.class,
                        EntityTrackerSystems.Visible::new
                );
        ComponentType<EntityStore, UIComponentList> uiListType =
                registry.registerComponent(
                        UIComponentList.class,
                        UIComponentList::new
                );
        ComponentType<EntityStore, NonTicking<EntityStore>> nonTickingType =
                registry.getNonTickingComponentType();
        Store<EntityStore> store = registry.addStore(null, null);
        try {
            UIComponentList uiList = new UIComponentList();
            setComponentIds(uiList, 7, 11);
            EntityTrackerSystems.Visible staleVisibility =
                    new EntityTrackerSystems.Visible();
            Holder<EntityStore> targetHolder = registry.newHolder();
            targetHolder.addComponent(nonTickingType, NonTicking.get());
            targetHolder.addComponent(visibleType, staleVisibility);
            targetHolder.addComponent(uiListType, uiList);
            Ref<EntityStore> target =
                    store.addEntity(targetHolder, AddReason.SPAWN);
            Ref<EntityStore> viewerRef = store.addEntity(
                    registry.newHolder(), AddReason.SPAWN
            );
            EntityTrackerSystems.EntityViewer viewer =
                    new EntityTrackerSystems.EntityViewer(64, null);
            viewer.visible.add(target);
            staleVisibility.addViewerParallel(viewerRef, viewer);

            HytaleCaptureReleaseProjectionGateway.releaseRuntimeHold(
                    store,
                    target,
                    visibleType,
                    nonTickingType
            );

            assertNull(store.getComponent(target, nonTickingType));
            advanceVisibilityTracker(store, visibleType);
            EntityTrackerSystems.Visible refreshedVisibility =
                    store.ensureAndGetComponent(target, visibleType);
            refreshedVisibility.addViewerParallel(viewerRef, viewer);
            queueUiComponentUpdates(store, visibleType, uiListType);

            EntityTrackerSystems.EntityUpdate entityUpdate =
                    viewer.updates.get(target);
            assertNotNull(entityUpdate);
            UIComponentsUpdate uiUpdate = Arrays.stream(
                            entityUpdate.toUpdatesArray()
                    )
                    .filter(UIComponentsUpdate.class::isInstance)
                    .map(UIComponentsUpdate.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertArrayEquals(new int[] {7, 11}, uiUpdate.components);
            assertTrue(
                    refreshedVisibility.newlyVisibleTo.containsKey(viewerRef)
            );
        } finally {
            registry.removeStore(store);
            registry.shutdown();
        }
    }

    @Test
    void repeatedReleaseWithoutHoldDoesNotReinitializeUi() throws Exception {
        ComponentRegistry<EntityStore> registry = new ComponentRegistry<>();
        ComponentType<EntityStore, EntityTrackerSystems.Visible> visibleType =
                registry.registerComponent(
                        EntityTrackerSystems.Visible.class,
                        EntityTrackerSystems.Visible::new
                );
        ComponentType<EntityStore, UIComponentList> uiListType =
                registry.registerComponent(
                        UIComponentList.class,
                        UIComponentList::new
                );
        ComponentType<EntityStore, NonTicking<EntityStore>> nonTickingType =
                registry.getNonTickingComponentType();
        Store<EntityStore> store = registry.addStore(null, null);
        try {
            UIComponentList uiList = new UIComponentList();
            setComponentIds(uiList, 7, 11);
            EntityTrackerSystems.Visible stableVisibility =
                    new EntityTrackerSystems.Visible();
            Holder<EntityStore> targetHolder = registry.newHolder();
            targetHolder.addComponent(visibleType, stableVisibility);
            targetHolder.addComponent(uiListType, uiList);
            Ref<EntityStore> target =
                    store.addEntity(targetHolder, AddReason.SPAWN);
            Ref<EntityStore> viewerRef = store.addEntity(
                    registry.newHolder(), AddReason.SPAWN
            );
            EntityTrackerSystems.EntityViewer viewer =
                    new EntityTrackerSystems.EntityViewer(64, null);
            viewer.visible.add(target);
            stableVisibility.addViewerParallel(viewerRef, viewer);

            HytaleCaptureReleaseProjectionGateway.releaseRuntimeHold(
                    store,
                    target,
                    visibleType,
                    nonTickingType
            );

            advanceVisibilityTracker(store, visibleType);
            EntityTrackerSystems.Visible visibility =
                    store.ensureAndGetComponent(target, visibleType);
            visibility.addViewerParallel(viewerRef, viewer);
            queueUiComponentUpdates(store, visibleType, uiListType);

            assertNull(viewer.updates.get(target));
        } finally {
            registry.removeStore(store);
            registry.shutdown();
        }
    }

    private void advanceVisibilityTracker(
            Store<EntityStore> store,
            ComponentType<EntityStore, EntityTrackerSystems.Visible>
                    visibleType
    ) {
        EntityTrackerSystems.ClearPreviouslyVisible clear =
                new EntityTrackerSystems.ClearPreviouslyVisible(visibleType);
        store.forEachChunk(clear.getQuery(), (chunk, commandBuffer) -> {
            for (int index = 0; index < chunk.size(); index++) {
                clear.tick(
                        0.0F, index, chunk, store, commandBuffer
                );
            }
        });
    }

    private void queueUiComponentUpdates(
            Store<EntityStore> store,
            ComponentType<EntityStore, EntityTrackerSystems.Visible>
                    visibleType,
            ComponentType<EntityStore, UIComponentList> uiListType
    ) {
        UIComponentSystems.Update update =
                new UIComponentSystems.Update(visibleType, uiListType);
        store.forEachChunk(update.getQuery(), (chunk, commandBuffer) -> {
            for (int index = 0; index < chunk.size(); index++) {
                update.tick(
                        0.0F, index, chunk, store, commandBuffer
                );
            }
        });
    }

    private void setComponentIds(
            UIComponentList list,
            int... componentIds
    ) throws ReflectiveOperationException {
        Field field = UIComponentList.class.getDeclaredField("componentIds");
        field.setAccessible(true);
        field.set(list, componentIds);
    }
}
