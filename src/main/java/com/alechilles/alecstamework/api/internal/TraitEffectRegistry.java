package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.TraitEffectApi;
import com.alechilles.alecstamework.api.TraitEffectContext;
import com.alechilles.alecstamework.api.TraitEffectContribution;
import com.alechilles.alecstamework.api.TraitEffectHandler;
import com.alechilles.alecstamework.api.NpcProfilesApi;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Stores public custom trait-effect handlers and applies them from Tamework trait resyncs.
 */
public final class TraitEffectRegistry implements TraitEffectApi, TraitEffectRuntime {
    @Nullable
    private final HytaleLogger logger;
    @Nullable
    private final NpcProfilesApi profiles;
    private final ConcurrentHashMap<String, TraitEffectHandler> handlers = new ConcurrentHashMap<>();

    public TraitEffectRegistry(
            @Nullable HytaleLogger logger,
            @Nullable NpcProfilesApi profiles
    ) {
        this.logger = logger;
        this.profiles = profiles;
    }

    @Override
    @Nonnull
    public AutoCloseable registerEffectKey(@Nonnull String effectKey, @Nonnull TraitEffectHandler handler) {
        String normalizedKey = requireNormalizedKey(effectKey);
        TraitEffectHandler normalizedHandler = Objects.requireNonNull(handler, "handler");
        handlers.put(normalizedKey, normalizedHandler);
        return () -> handlers.remove(normalizedKey, normalizedHandler);
    }

    @Override
    @Nonnull
    public Set<String> listEffectKeys() {
        return Set.copyOf(handlers.keySet());
    }

    @Override
    public void applyRegisteredEffects(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        if (npcRef == null || store == null || !npcRef.isValid() || handlers.isEmpty()) {
            return;
        }
        ComponentType<EntityStore, TameworkTraitsComponent> type = TameworkTraitsComponent.getComponentType();
        TameworkTraitsComponent component = type != null ? store.getComponent(npcRef, type) : null;
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        TwTraitConfig config = resolveTraitConfig(component, roleId);
        String traitConfigId = resolveTraitConfigId(component, config);
        UUID npcUuid = resolveNpcUuid(npcRef, store);
        String profileId = resolveProfileId(npcUuid);

        for (Map.Entry<String, TraitEffectHandler> entry : handlers.entrySet()) {
            String effectKey = entry.getKey();
            ResolvedTraitEffect resolved = resolveEffect(effectKey, component, config);
            TraitEffectContext context = new TraitEffectContext(
                    effectKey,
                    resolved.value(),
                    resolved.contributions(),
                    npcRef,
                    store,
                    npcUuid,
                    profileId,
                    roleId,
                    traitConfigId
            );
            invokeHandler(effectKey, entry.getValue(), context);
        }
    }

    @Nonnull
    static ResolvedTraitEffect resolveEffect(@Nullable String effectKey,
                                             @Nullable TameworkTraitsComponent component,
                                             @Nullable TwTraitConfig config) {
        String normalizedEffectKey = normalize(effectKey);
        if (component == null || config == null || normalizedEffectKey == null) {
            return ResolvedTraitEffect.empty();
        }
        Map<String, TwTraitConfig.TraitDefinition> definitionById = buildDefinitionMap(config);
        if (definitionById.isEmpty()) {
            return ResolvedTraitEffect.empty();
        }

        double multiplier = 1.0;
        ArrayList<TraitEffectContribution> contributions = new ArrayList<>();
        for (TameworkTraitsComponent.TraitValue value : component.getTraitValues()) {
            if (value == null) {
                continue;
            }
            String normalizedId = normalize(value.getId());
            if (normalizedId == null) {
                continue;
            }
            TwTraitConfig.TraitDefinition definition = definitionById.get(normalizedId);
            if (definition == null) {
                continue;
            }
            String definitionEffect = definition.getEffectKey();
            String normalizedDefinitionEffect = normalize(definitionEffect);
            if (!normalizedEffectKey.equals(normalizedDefinitionEffect)) {
                continue;
            }
            double traitValue = value.getValue();
            if (!Double.isFinite(traitValue)) {
                continue;
            }
            multiplier *= traitValue;
            contributions.add(new TraitEffectContribution(
                    definition.getId(),
                    definition.getDisplayName(),
                    traitValue,
                    definitionEffect
            ));
        }
        if (contributions.isEmpty()) {
            return ResolvedTraitEffect.empty();
        }
        return new ResolvedTraitEffect(multiplier, contributions);
    }

    @Nonnull
    private String requireNormalizedKey(@Nullable String rawKey) {
        String normalized = normalize(rawKey);
        if (normalized == null) {
            throw new IllegalArgumentException("effect key must be nonblank.");
        }
        return normalized;
    }

    @Nullable
    private static String normalize(@Nullable String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        return rawValue.trim().toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static TwTraitConfig resolveTraitConfig(@Nullable TameworkTraitsComponent component,
                                                    @Nullable String roleId) {
        if (roleId != null && !roleId.isBlank()) {
            TwTraitConfig byRole = TwTraitConfig.resolveForRole(roleId);
            if (byRole != null) {
                return byRole;
            }
        }
        if (component == null) {
            return null;
        }
        String configId = component.getConfigId();
        if (configId == null || configId.isBlank()) {
            return null;
        }
        return TwTraitConfig.resolveById(configId);
    }

    @Nullable
    private static String resolveTraitConfigId(@Nullable TameworkTraitsComponent component,
                                               @Nullable TwTraitConfig config) {
        if (config != null && config.getId() != null && !config.getId().isBlank()) {
            return config.getId();
        }
        if (component != null && component.getConfigId() != null && !component.getConfigId().isBlank()) {
            return component.getConfigId();
        }
        return null;
    }

    @Nullable
    private static UUID resolveNpcUuid(@Nonnull Ref<EntityStore> npcRef, @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        if (uuidType == null) {
            return null;
        }
        UUIDComponent uuidComponent = store.getComponent(npcRef, uuidType);
        return uuidComponent != null ? uuidComponent.getUuid() : null;
    }

    @Nullable
    private String resolveProfileId(@Nullable UUID npcUuid) {
        if (npcUuid == null || profiles == null) {
            return null;
        }
        return profiles.resolveProfileId(npcUuid).orElse(null);
    }

    private static Map<String, TwTraitConfig.TraitDefinition> buildDefinitionMap(TwTraitConfig config) {
        HashMap<String, TwTraitConfig.TraitDefinition> map = new HashMap<>();
        for (TwTraitConfig.TraitDefinition definition : config.getTraits()) {
            if (definition == null) {
                continue;
            }
            String normalized = normalize(definition.getId());
            if (normalized == null || map.containsKey(normalized)) {
                continue;
            }
            map.put(normalized, definition);
        }
        return map;
    }

    boolean invokeHandler(@Nonnull String effectKey,
                          @Nonnull TraitEffectHandler handler,
                          @Nonnull TraitEffectContext context) {
        try {
            return handler.apply(context);
        } catch (Throwable error) {
            logHandlerFailure(effectKey, error);
            return false;
        }
    }

    private void logHandlerFailure(@Nonnull String effectKey, @Nullable Throwable error) {
        if (logger == null) {
            return;
        }
        String message = "Tamework trait effect handler failed: " + effectKey;
        if (error == null) {
            logger.at(Level.WARNING).log(message);
        } else {
            logger.at(Level.WARNING).withCause(error).log(message);
        }
    }

    record ResolvedTraitEffect(double value, @Nonnull List<TraitEffectContribution> contributions) {
        private static final ResolvedTraitEffect EMPTY = new ResolvedTraitEffect(1.0, List.of());

        @Nonnull
        static ResolvedTraitEffect empty() {
            return EMPTY;
        }

        ResolvedTraitEffect {
            contributions = List.copyOf(contributions);
        }
    }
}
