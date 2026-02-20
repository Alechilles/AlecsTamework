package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.UUIDBinaryCodec;
import com.hypixel.hytale.common.util.ArrayUtil;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Stores per-owner command tool links for an NPC.
 */
public final class TameworkCommandLinksComponent implements Component<EntityStore> {
    public static final BuilderCodec<TameworkCommandLinksComponent> CODEC = BuilderCodec.builder(
            TameworkCommandLinksComponent.class,
            TameworkCommandLinksComponent::new
    )
        .append(
            new KeyedCodec<>("OwnerId", new UUIDBinaryCodec()),
            TameworkCommandLinksComponent::setOwnerId,
            TameworkCommandLinksComponent::getOwnerId
        )
        .add()
        .append(
            new KeyedCodec<>("ToolIds", Codec.STRING_ARRAY),
            TameworkCommandLinksComponent::setToolIds,
            TameworkCommandLinksComponent::getToolIds
        )
        .add()
        .append(
            new KeyedCodec<>("HasHome", Codec.BOOLEAN),
            TameworkCommandLinksComponent::setHasHome,
            TameworkCommandLinksComponent::isHasHome
        )
        .add()
        .append(
            new KeyedCodec<>("HomeX", Codec.DOUBLE),
            TameworkCommandLinksComponent::setHomeX,
            TameworkCommandLinksComponent::getHomeX
        )
        .add()
        .append(
            new KeyedCodec<>("HomeY", Codec.DOUBLE),
            TameworkCommandLinksComponent::setHomeY,
            TameworkCommandLinksComponent::getHomeY
        )
        .add()
        .append(
            new KeyedCodec<>("HomeZ", Codec.DOUBLE),
            TameworkCommandLinksComponent::setHomeZ,
            TameworkCommandLinksComponent::getHomeZ
        )
        .add()
        .build();

    private UUID ownerId;
    private String[] toolIds = ArrayUtil.EMPTY_STRING_ARRAY;
    private boolean hasHome;
    private double homeX;
    private double homeY;
    private double homeZ;

    public TameworkCommandLinksComponent() {
    }

    public TameworkCommandLinksComponent(UUID ownerId, String[] toolIds) {
        this(ownerId, toolIds, null);
    }

    public TameworkCommandLinksComponent(UUID ownerId, String[] toolIds, Vector3d homePosition) {
        this.ownerId = ownerId;
        this.toolIds = sanitizeToolIds(toolIds);
        setHomePosition(homePosition);
    }

    public static ComponentType<EntityStore, TameworkCommandLinksComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getCommandLinksComponentType() : null;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public String[] getToolIds() {
        return toolIds;
    }

    public void setToolIds(String[] toolIds) {
        this.toolIds = sanitizeToolIds(toolIds);
    }

    public boolean isHasHome() {
        return hasHome;
    }

    public void setHasHome(boolean hasHome) {
        this.hasHome = hasHome;
    }

    public double getHomeX() {
        return homeX;
    }

    public void setHomeX(double homeX) {
        this.homeX = homeX;
    }

    public double getHomeY() {
        return homeY;
    }

    public void setHomeY(double homeY) {
        this.homeY = homeY;
    }

    public double getHomeZ() {
        return homeZ;
    }

    public void setHomeZ(double homeZ) {
        this.homeZ = homeZ;
    }

    public boolean hasHome() {
        return hasHome;
    }

    public Vector3d getHomePosition() {
        if (!hasHome) {
            return null;
        }
        return new Vector3d(homeX, homeY, homeZ);
    }

    public void setHomePosition(Vector3d homePosition) {
        if (homePosition == null) {
            this.hasHome = false;
            this.homeX = 0.0;
            this.homeY = 0.0;
            this.homeZ = 0.0;
            return;
        }
        this.hasHome = true;
        this.homeX = homePosition.x;
        this.homeY = homePosition.y;
        this.homeZ = homePosition.z;
    }

    public boolean containsToolId(String toolId) {
        if (toolId == null || toolId.isBlank() || toolIds == null || toolIds.length == 0) {
            return false;
        }
        for (String value : toolIds) {
            if (toolId.equals(value)) {
                return true;
            }
        }
        return false;
    }

    public TameworkCommandLinksComponent withToolIdAdded(String toolId) {
        Set<String> values = new LinkedHashSet<>();
        if (toolIds != null) {
            values.addAll(Arrays.asList(toolIds));
        }
        if (toolId != null && !toolId.isBlank()) {
            values.add(toolId);
        }
        return new TameworkCommandLinksComponent(ownerId, values.toArray(new String[0]), getHomePosition());
    }

    public TameworkCommandLinksComponent withToolIdRemoved(String toolId) {
        Set<String> values = new LinkedHashSet<>();
        if (toolIds != null) {
            values.addAll(Arrays.asList(toolIds));
        }
        values.remove(toolId);
        return new TameworkCommandLinksComponent(ownerId, values.toArray(new String[0]), getHomePosition());
    }

    private static String[] sanitizeToolIds(String[] values) {
        if (values == null || values.length == 0) {
            return ArrayUtil.EMPTY_STRING_ARRAY;
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            unique.add(value);
        }
        return unique.isEmpty() ? ArrayUtil.EMPTY_STRING_ARRAY : unique.toArray(new String[0]);
    }

    @Override
    public TameworkCommandLinksComponent clone() {
        return new TameworkCommandLinksComponent(
                ownerId,
                toolIds == null ? ArrayUtil.EMPTY_STRING_ARRAY : toolIds.clone(),
                getHomePosition()
        );
    }
}
