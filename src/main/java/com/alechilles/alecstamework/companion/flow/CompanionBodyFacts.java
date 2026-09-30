package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.live.CompanionSummaries;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Reads what a loaded body tells the index. Call on the body's world thread. */
public final class CompanionBodyFacts {
    private static final String UNKNOWN_ROLE = "unknown";

    private CompanionBodyFacts() {
    }

    /** Returns null when the body has no UUID, no position or no world name. */
    @Nullable
    public static CompanionTransitions.BodyFacts read(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                                      @Nonnull CompanionSummaries summaries) {
        if (!ref.isValid()) {
            return null;
        }
        UUIDComponent uuid = store.getComponent(ref, UUIDComponent.getComponentType());
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        UUID npcUuid = uuid == null ? null : uuid.getUuid();
        Vector3d position = transform == null ? null : transform.getPosition();
        String world = worldName(store);
        if (npcUuid == null || position == null || world == null) {
            return null;
        }
        TameworkOwnerComponent owner = get(store, ref, TameworkOwnerComponent.getComponentType());
        TameworkNpcNameComponent name = get(store, ref, TameworkNpcNameComponent.getComponentType());
        TameworkCommandLinksComponent links = get(store, ref, TameworkCommandLinksComponent.getComponentType());
        String roleId = CompanionRoleIdResolver.resolveRoleId(ref, store);
        String displayName = name == null ? null : name.getName();
        CompanionSummary summary = summaries.capture(ref, store, System.currentTimeMillis());
        return new CompanionTransitions.BodyFacts(
                npcUuid,
                owner == null ? null : owner.getOwnerId(),
                owner == null ? null : owner.getOwnerName(),
                roleId == null || roleId.isBlank() ? UNKNOWN_ROLE : roleId,
                displayName == null || displayName.isBlank() ? null : displayName,
                world,
                position.x, position.y, position.z,
                toolIds(links),
                summary == null ? CompanionSummary.EMPTY : summary);
    }

    @Nullable
    private static String worldName(Store<EntityStore> store) {
        EntityStore external = store.getExternalData();
        World world = external == null ? null : external.getWorld();
        String name = world == null ? null : world.getName();
        return name == null || name.isBlank() ? null : name;
    }

    private static List<String> toolIds(@Nullable TameworkCommandLinksComponent links) {
        String[] ids = links == null ? null : links.getToolIds();
        if (ids == null || ids.length == 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>(ids.length);
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                out.add(id);
            }
        }
        return out;
    }

    @Nullable
    private static <T extends Component<EntityStore>> T get(
            Store<EntityStore> store, Ref<EntityStore> ref, @Nullable ComponentType<EntityStore, T> type) {
        return type == null ? null : store.getComponent(ref, type);
    }
}
