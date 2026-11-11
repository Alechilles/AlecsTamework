package com.alechilles.alecstamework.npc.sensors.builders;

import com.alechilles.alecstamework.api.access.AccessLevel;
import com.alechilles.alecstamework.npc.sensors.SensorTameworkHasAccess;
import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.EnumHolder;
import com.hypixel.hytale.server.npc.instructions.Sensor;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Builder for SensorTameworkHasAccess.
 */
public final class BuilderSensorTameworkHasAccess extends TameworkSensorBuilderBase {
    public static final String BUILDER_ID = "TameworkHasAccess";
    /** JSON key of the lowest access level that matches. Shared with the entity filter. */
    public static final String MIN_LEVEL_KEY = "MinLevel";
    public static final String MIN_LEVEL_DESCRIPTION =
            "Lowest access level that matches: Caretaker, Handler, Manager, or Owner.";

    private final EnumHolder<MinLevel> minLevel = new EnumHolder<>();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Nonnull
    @Override
    public BuilderSensorTameworkHasAccess readConfig(@Nonnull JsonElement data) {
        super.readConfig(data);
        getEnum(data, MIN_LEVEL_KEY, minLevel, MinLevel.class, MinLevel.CARETAKER,
                BuilderDescriptorState.Stable, MIN_LEVEL_DESCRIPTION, null);
        return this;
    }

    @Nonnull
    @Override
    public Sensor build(@Nonnull BuilderSupport support) {
        return new SensorTameworkHasAccess(this, support);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "True when the current interaction player has access to the NPC.";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Checks whether the interacting player is the NPC's owner or holds shared access to it "
                + "at MinLevel or higher. Use TameworkIsOwner when only the owner may match.";
    }

    /** The configured level, read once when the sensor is built. */
    @Nonnull
    public AccessLevel getMinLevel(@Nonnull BuilderSupport support) {
        return minLevel.get(support.getExecutionContext()).level();
    }

    /** The levels {@code MinLevel} accepts. {@link AccessLevel#NONE} is left out: it would match everyone. */
    public enum MinLevel implements Supplier<String> {
        CARETAKER(AccessLevel.CARETAKER, "The owner and anyone they share with"),
        HANDLER(AccessLevel.HANDLER, "The owner, managers and handlers"),
        MANAGER(AccessLevel.MANAGER, "The owner and managers"),
        OWNER(AccessLevel.OWNER, "The owner only");

        private final AccessLevel level;
        private final String description;

        MinLevel(@Nonnull AccessLevel level, @Nonnull String description) {
            this.level = level;
            this.description = description;
        }

        @Nonnull
        public AccessLevel level() {
            return level;
        }

        @Override
        public String get() {
            return description;
        }
    }
}
