package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Keeps command-link and naming authorization aligned with the canonical owner component.
 *
 * <p>An ownership transfer invalidates command-tool links instead of silently transferring an old
 * player's tool authorization. Existing display-name metadata is retained, but its authority is
 * reassigned to the new canonical owner (or cleared with canonical ownership).
 */
final class OwnerDerivedAuthorityMutationService {

    @Nonnull
    Snapshot capture(@Nonnull Ref<EntityStore> npcRef, @Nonnull Store<EntityStore> store) {
        return new Snapshot(
                cloneComponent(component(npcRef, store, TameworkCommandLinksComponent.getComponentType())),
                cloneComponent(component(npcRef, store, TameworkNpcNameComponent.getComponentType()))
        );
    }

    void applyImmediate(@Nonnull Ref<EntityStore> npcRef,
                        @Nonnull Store<EntityStore> store,
                        @Nonnull Snapshot before,
                        @Nullable UUID expectedOwnerId,
                        @Nullable UUID newOwnerId) {
        if (newOwnerId != null && Objects.equals(expectedOwnerId, newOwnerId)) {
            return;
        }
        writeImmediate(npcRef, store, project(before, expectedOwnerId, newOwnerId));
    }

    void applyBuffered(@Nonnull Ref<EntityStore> npcRef,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull Snapshot before,
                       @Nullable UUID expectedOwnerId,
                       @Nullable UUID newOwnerId) {
        if (newOwnerId != null && Objects.equals(expectedOwnerId, newOwnerId)) {
            return;
        }
        Snapshot projected = project(before, expectedOwnerId, newOwnerId);
        writeBuffered(
                npcRef,
                commandBuffer,
                TameworkCommandLinksComponent.getComponentType(),
                projected.commandLinks()
        );
        writeBuffered(
                npcRef,
                commandBuffer,
                TameworkNpcNameComponent.getComponentType(),
                projected.npcName()
        );
    }

    boolean restoreImmediate(@Nonnull Ref<EntityStore> npcRef,
                             @Nonnull Store<EntityStore> store,
                             @Nonnull Snapshot before) {
        try {
            writeImmediate(npcRef, store, before);
            return before.sameAs(capture(npcRef, store));
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    @Nonnull
    static Snapshot project(@Nonnull Snapshot before,
                            @Nullable UUID expectedOwnerId,
                            @Nullable UUID newOwnerId) {
        if (newOwnerId != null && Objects.equals(expectedOwnerId, newOwnerId)) {
            return before.copy();
        }
        TameworkCommandLinksComponent commandLinks = preservePrelinkedInitialOwner(
                before.commandLinks(), expectedOwnerId, newOwnerId
        ) ? cloneComponent(before.commandLinks()) : null;
        TameworkNpcNameComponent npcName = cloneComponent(before.npcName());
        if (npcName != null) {
            npcName.setOwnerId(newOwnerId);
        }
        return new Snapshot(commandLinks, npcName);
    }

    private static boolean preservePrelinkedInitialOwner(
            @Nullable TameworkCommandLinksComponent links,
            @Nullable UUID expectedOwnerId,
            @Nullable UUID newOwnerId
    ) {
        return expectedOwnerId == null
                && newOwnerId != null
                && links != null
                && newOwnerId.equals(links.getOwnerId());
    }

    private static void writeImmediate(@Nonnull Ref<EntityStore> npcRef,
                                       @Nonnull Store<EntityStore> store,
                                       @Nonnull Snapshot values) {
        writeImmediate(
                npcRef,
                store,
                TameworkCommandLinksComponent.getComponentType(),
                values.commandLinks()
        );
        writeImmediate(
                npcRef,
                store,
                TameworkNpcNameComponent.getComponentType(),
                values.npcName()
        );
    }

    private static <T extends Component<EntityStore>> void writeImmediate(
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull Store<EntityStore> store,
            @Nullable ComponentType<EntityStore, T> type,
            @Nullable T value
    ) {
        if (type == null) {
            return;
        }
        if (value == null) {
            store.tryRemoveComponent(npcRef, type);
        } else {
            store.putComponent(npcRef, type, cloneComponent(value));
        }
    }

    private static <T extends Component<EntityStore>> void writeBuffered(
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nullable ComponentType<EntityStore, T> type,
            @Nullable T value
    ) {
        if (type == null) {
            return;
        }
        if (value == null) {
            commandBuffer.tryRemoveComponent(npcRef, type);
        } else {
            commandBuffer.putComponent(npcRef, type, cloneComponent(value));
        }
    }

    @Nullable
    private static <T extends Component<EntityStore>> T component(
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull Store<EntityStore> store,
            @Nullable ComponentType<EntityStore, T> type
    ) {
        return type == null ? null : store.getComponent(npcRef, type);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static <T extends Component<EntityStore>> T cloneComponent(@Nullable T component) {
        return component == null ? null : (T) component.clone();
    }

    record Snapshot(@Nullable TameworkCommandLinksComponent commandLinks,
                    @Nullable TameworkNpcNameComponent npcName) {
        @Nonnull
        Snapshot copy() {
            return new Snapshot(cloneComponent(commandLinks), cloneComponent(npcName));
        }

        boolean sameAs(@Nonnull Snapshot other) {
            return sameLinks(commandLinks, other.commandLinks)
                    && sameName(npcName, other.npcName);
        }

        private static boolean sameLinks(@Nullable TameworkCommandLinksComponent left,
                                         @Nullable TameworkCommandLinksComponent right) {
            if (left == null || right == null) {
                return left == right;
            }
            return Objects.equals(left.getOwnerId(), right.getOwnerId())
                    && java.util.Arrays.equals(left.getToolIds(), right.getToolIds())
                    && left.isHasHome() == right.isHasHome()
                    && Double.compare(left.getHomeX(), right.getHomeX()) == 0
                    && Double.compare(left.getHomeY(), right.getHomeY()) == 0
                    && Double.compare(left.getHomeZ(), right.getHomeZ()) == 0;
        }

        private static boolean sameName(@Nullable TameworkNpcNameComponent left,
                                        @Nullable TameworkNpcNameComponent right) {
            if (left == null || right == null) {
                return left == right;
            }
            return Objects.equals(left.getName(), right.getName())
                    && Objects.equals(left.getOwnerId(), right.getOwnerId())
                    && left.getLastUpdatedMs() == right.getLastUpdatedMs()
                    && left.getSource() == right.getSource();
        }
    }
}
