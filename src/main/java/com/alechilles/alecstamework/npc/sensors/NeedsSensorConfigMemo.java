package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.npc.progression.NeedsConfigResolver;
import java.util.Objects;
import javax.annotation.Nullable;

/** Caches one sensor's compiled needs config until its key or generation changes. */
final class NeedsSensorConfigMemo {
    private static final Source DEFAULT_SOURCE = new Source() {
        @Override
        public long generation() {
            return NeedsConfigResolver.sensorConfigGeneration();
        }

        @Override
        public NeedsConfigResolver.NeedsSensorConfig resolve(
                @Nullable String roleId,
                @Nullable String configId) {
            return NeedsConfigResolver.resolveCompiledSensorConfig(roleId, configId);
        }
    };

    private final Source source;
    @Nullable
    private String roleId;
    @Nullable
    private String configId;
    @Nullable
    private NeedsConfigResolver.NeedsSensorConfig config;
    private long generation;
    private boolean initialized;

    NeedsSensorConfigMemo() {
        this(DEFAULT_SOURCE);
    }

    NeedsSensorConfigMemo(Source source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Nullable
    NeedsConfigResolver.NeedsSensorConfig resolve(
            @Nullable String nextRoleId,
            @Nullable String nextConfigId) {
        long nextGeneration = source.generation();
        if (initialized
                && generation == nextGeneration
                && Objects.equals(roleId, nextRoleId)
                && Objects.equals(configId, nextConfigId)) {
            return config;
        }
        roleId = nextRoleId;
        configId = nextConfigId;
        generation = nextGeneration;
        config = source.resolve(nextRoleId, nextConfigId);
        initialized = true;
        return config;
    }

    /** Supplies reload generation and compiled values without retaining ECS data. */
    interface Source {
        long generation();

        @Nullable
        NeedsConfigResolver.NeedsSensorConfig resolve(
                @Nullable String roleId,
                @Nullable String configId);
    }
}
