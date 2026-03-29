package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.EntityPositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.IPositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.hypixel.hytale.server.npc.util.expression.ExecutionContext;
import com.hypixel.hytale.server.npc.util.expression.Scope;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Logs a compact combat diagnostics snapshot for CAE debugging.
 */
public final class ActionTameworkDebugCombatSnapshot extends TameworkActionBase {
    private static final Logger FALLBACK_LOGGER = Logger.getLogger(ActionTameworkDebugCombatSnapshot.class.getName());

    private static final String[] DEFAULT_TARGET_SLOTS = {
            "LockedTarget",
            "CAETargetSlot",
            "MasterTarget"
    };

    private static final String[] DEFAULT_NUMBER_PARAMS = {
            "AttackDistance",
            "CombatBehaviorDistance",
            "DesiredAttackDistanceRange",
            "DesiredAttackDistance",
            "CombatBackwardsRelativeSpeed"
    };

    @Nullable
    private final String message;
    @Nonnull
    private final String[] targetSlots;
    @Nonnull
    private final int[] targetSlotIndices;
    @Nonnull
    private final String[] numberParams;
    @Nullable
    private final StdScope globalScopeSnapshot;
    @Nullable
    private final StdScope executionScopeSnapshot;
    @Nullable
    private final StdScope sensorScopeSnapshot;

    public ActionTameworkDebugCombatSnapshot(@Nonnull BuilderActionTameworkDebugCombatSnapshot builder,
                                             @Nonnull BuilderSupport support) {
        super(builder);
        this.message = builder.getMessage(support);
        this.targetSlots = resolveConfiguredOrDefault(builder.getTargetSlots(support), DEFAULT_TARGET_SLOTS);
        this.targetSlotIndices = resolveTargetSlotIndices(this.targetSlots, support);
        this.numberParams = resolveConfiguredOrDefault(builder.getNumberParams(support), DEFAULT_NUMBER_PARAMS);
        this.globalScopeSnapshot = snapshotScope(support.getGlobalScope());
        ExecutionContext executionContext = support.getExecutionContext();
        this.executionScopeSnapshot = snapshotScope(executionContext != null ? executionContext.getScope() : null);
        this.sensorScopeSnapshot = snapshotScope(support.getSensorScope());
    }

    @Override
    public boolean canExecute(@Nullable Ref<EntityStore> npcRef,
                              @Nullable Role role,
                              @Nullable InfoProvider infoProvider,
                              double dt,
                              @Nullable Store<EntityStore> store) {
        return npcRef != null && store != null && npcRef.isValid();
    }

    @Override
    public boolean execute(@Nullable Ref<EntityStore> npcRef,
                           @Nullable Role role,
                           @Nullable InfoProvider infoProvider,
                           double dt,
                           @Nullable Store<EntityStore> store) {
        if (!canExecute(npcRef, role, infoProvider, dt, store)) {
            return false;
        }

        Vector3d npcPosition = resolvePosition(npcRef, store);
        Ref<EntityStore> interactionTarget = role != null && role.getStateSupport() != null
                ? role.getStateSupport().getInteractionIterationTarget()
                : null;
        Ref<EntityStore> infoProviderTarget = resolveInfoProviderTarget(infoProvider);

        StringBuilder line = new StringBuilder(640);
        line.append("Debug combat snapshot: npc=").append(resolveNpcId(npcRef, store))
                .append(" message=").append(formatProbe(message))
                .append(" role=").append(role != null ? role.getRoleName() : "<null>")
                .append(" state=").append(describeState(role))
                .append(" interactionTarget=").append(describeRef(interactionTarget, store, npcPosition))
                .append(" infoTarget=").append(describeRef(infoProviderTarget, store, npcPosition))
                .append(" markedTargets=").append(describeMarkedTargets(role, store, npcPosition))
                .append(" params=").append(describeNumberParams(role))
                .append(" info=").append(describeInfoProvider(infoProvider));

        Tamework instance = Tamework.getInstance();
        if (instance != null && instance.getLogger() != null) {
            instance.getLogger().at(Level.INFO).log(line.toString());
            return true;
        }
        FALLBACK_LOGGER.log(Level.INFO, line.toString());
        return true;
    }

    @Nonnull
    private String describeState(@Nullable Role role) {
        if (role == null || role.getStateSupport() == null) {
            return "<none>";
        }
        int stateIndex = role.getStateSupport().getStateIndex();
        int subStateIndex = role.getStateSupport().getSubStateIndex();
        String stateName = role.getStateSupport().getStateName();
        String subStateName = null;
        if (role.getStateSupport().getStateHelper() != null && stateIndex >= 0 && subStateIndex >= 0) {
            subStateName = role.getStateSupport().getStateHelper().getSubStateName(stateIndex, subStateIndex);
        }
        if (stateName == null || stateName.isBlank()) {
            stateName = "<unknown>";
        }
        if (subStateName == null || subStateName.isBlank()) {
            subStateName = "<default>";
        }
        return stateName + "[" + stateIndex + "]/" + subStateName + "[" + subStateIndex + "]";
    }

    @Nonnull
    private String describeMarkedTargets(@Nullable Role role,
                                         @Nonnull Store<EntityStore> store,
                                         @Nullable Vector3d npcPosition) {
        if (role == null || role.getMarkedEntitySupport() == null) {
            return "<none>";
        }
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < targetSlots.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            String slotName = targetSlots[i];
            int slotIndex = i < targetSlotIndices.length ? targetSlotIndices[i] : Integer.MIN_VALUE;
            Ref<EntityStore> target = readMarkedEntity(role, slotName, slotIndex);
            out.append(slotName)
                    .append("[")
                    .append(slotIndex == Integer.MIN_VALUE ? "?" : slotIndex)
                    .append("]=")
                    .append(describeRef(target, store, npcPosition));
        }
        out.append("]");
        return out.toString();
    }

    @Nonnull
    private String describeNumberParams(@Nullable Role role) {
        if (role == null) {
            return "<none>";
        }
        List<StdScope> scopes = collectCandidateScopes(role);
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < numberParams.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            String key = numberParams[i];
            Double scalar = readNumberParam(scopes, key);
            if (scalar != null && Double.isFinite(scalar)) {
                out.append(key).append("=").append(formatNumber(scalar));
                continue;
            }
            double[] values = readNumberArrayParam(scopes, key);
            if (values != null && values.length > 0) {
                out.append(key).append("=").append(formatNumberArray(values));
                continue;
            }
            out.append(key).append("=<missing>");
        }
        out.append("]");
        return out.toString();
    }

    @Nonnull
    private String describeInfoProvider(@Nullable InfoProvider infoProvider) {
        if (infoProvider == null) {
            return "<none>";
        }
        StringBuilder out = new StringBuilder();
        out.append(infoProvider.getClass().getSimpleName())
                .append("{hasPosition=").append(infoProvider.hasPosition());
        IPositionProvider provider = infoProvider.hasPosition() ? infoProvider.getPositionProvider() : null;
        if (provider != null) {
            out.append(",provider=").append(provider.getClass().getSimpleName())
                    .append(",providerHasPosition=").append(provider.hasPosition());
            if (provider.hasPosition()) {
                out.append(",pos=")
                        .append(formatVector(provider.getX(), provider.getY(), provider.getZ()));
            }
        }
        out.append("}");
        return out.toString();
    }

    @Nullable
    private Ref<EntityStore> readMarkedEntity(@Nonnull Role role,
                                              @Nonnull String slotName,
                                              int slotIndex) {
        if (role.getMarkedEntitySupport() == null) {
            return null;
        }

        if (slotIndex != Integer.MIN_VALUE) {
            Ref<EntityStore> byIndex = role.getMarkedEntitySupport().getMarkedEntityRef(slotIndex);
            if (byIndex != null) {
                return byIndex;
            }
        }

        if (!slotName.isBlank()) {
            try {
                Method getterByName = role.getMarkedEntitySupport()
                        .getClass()
                        .getMethod("getMarkedEntity", String.class);
                Object value = getterByName.invoke(role.getMarkedEntitySupport(), slotName);
                Ref<EntityStore> resolved = extractEntityRef(value);
                if (resolved != null) {
                    return resolved;
                }
            } catch (Exception ignored) {
                // Best effort fallback.
            }
        }
        return null;
    }

    @Nullable
    private static Ref<EntityStore> extractEntityRef(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Ref<?>) {
            @SuppressWarnings("unchecked")
            Ref<EntityStore> cast = (Ref<EntityStore>) value;
            return cast;
        }
        for (String methodName : new String[] { "getRef", "getTarget", "getEntityRef", "getMarkedEntityRef" }) {
            try {
                Method method = value.getClass().getMethod(methodName);
                Object nested = method.invoke(value);
                Ref<EntityStore> resolved = extractEntityRef(nested);
                if (resolved != null) {
                    return resolved;
                }
            } catch (Exception ignored) {
                // Continue trying the next accessor.
            }
        }
        return null;
    }

    @Nonnull
    private List<StdScope> collectCandidateScopes(@Nonnull Role role) {
        List<StdScope> scopes = new ArrayList<>(8);

        if (role.getEntitySupport() != null) {
            addScope(scopes, snapshotScope(role.getEntitySupport().getSensorScope()));
            addScope(scopes, readScopeReflective(role.getEntitySupport(), "getExecutionScope"));
            addScope(scopes, readScopeReflective(role.getEntitySupport(), "getGlobalScope"));
            addScope(scopes, readScopeReflective(role.getEntitySupport(), "getScope"));
        }

        addScope(scopes, sensorScopeSnapshot);
        addScope(scopes, executionScopeSnapshot);
        addScope(scopes, globalScopeSnapshot);
        return scopes;
    }

    private static void addScope(@Nonnull List<StdScope> scopes, @Nullable StdScope candidate) {
        if (candidate == null) {
            return;
        }
        for (StdScope existing : scopes) {
            if (existing == candidate) {
                return;
            }
        }
        scopes.add(candidate);
    }

    @Nullable
    private static StdScope readScopeReflective(@Nonnull Object target, @Nonnull String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            if (value instanceof StdScope scope) {
                return StdScope.copyOf(scope);
            }
            if (value instanceof Scope scope) {
                return new StdScope(scope);
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    @Nullable
    private static Double readNumberParam(@Nonnull List<StdScope> scopes, @Nonnull String key) {
        if (key.isBlank()) {
            return null;
        }
        for (StdScope scope : scopes) {
            DoubleSupplier supplier;
            try {
                supplier = scope.getNumberSupplier(key);
            } catch (IllegalStateException ignored) {
                continue;
            }
            if (supplier != null) {
                return supplier.getAsDouble();
            }
        }
        return null;
    }

    @Nullable
    private static double[] readNumberArrayParam(@Nonnull List<StdScope> scopes, @Nonnull String key) {
        if (key.isBlank()) {
            return null;
        }
        for (StdScope scope : scopes) {
            Supplier<double[]> supplier;
            try {
                supplier = scope.getNumberArraySupplier(key);
            } catch (IllegalStateException ignored) {
                continue;
            }
            if (supplier != null) {
                return supplier.get();
            }
        }
        return null;
    }

    @Nonnull
    private static String describeRef(@Nullable Ref<EntityStore> ref,
                                      @Nonnull Store<EntityStore> store,
                                      @Nullable Vector3d npcPosition) {
        if (ref == null) {
            return "<none>";
        }
        if (!ref.isValid()) {
            return "<invalid>";
        }

        StringBuilder out = new StringBuilder();
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            out.append("player:");
            out.append(player.getUuid() != null ? player.getUuid() : ref);
        } else {
            NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
            if (npc != null) {
                out.append("npc:");
                out.append(npc.getUuid() != null ? npc.getUuid() : ref);
                if (npc.getRoleName() != null && !npc.getRoleName().isBlank()) {
                    out.append("(").append(npc.getRoleName()).append(")");
                }
            } else {
                out.append("entity:").append(ref);
            }
        }

        Vector3d targetPosition = resolvePosition(ref, store);
        if (npcPosition != null && targetPosition != null) {
            out.append("@d=").append(formatNumber(npcPosition.distanceTo(targetPosition)));
        }
        return out.toString();
    }

    @Nullable
    private static Vector3d resolvePosition(@Nullable Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            return null;
        }
        return new Vector3d(transform.getPosition());
    }

    @Nullable
    private static Ref<EntityStore> resolveInfoProviderTarget(@Nullable InfoProvider infoProvider) {
        if (infoProvider == null || !infoProvider.hasPosition()) {
            return null;
        }
        IPositionProvider provider = infoProvider.getPositionProvider();
        if (provider instanceof EntityPositionProvider entityProvider) {
            Ref<EntityStore> target = entityProvider.getTarget();
            if (target != null && target.isValid()) {
                return target;
            }
        }
        return null;
    }

    @Nonnull
    private static String resolveNpcId(@Nonnull Ref<EntityStore> npcRef, @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc != null && npc.getUuid() != null) {
            return npc.getUuid().toString();
        }
        return npcRef.toString();
    }

    @Nonnull
    private static String[] resolveConfiguredOrDefault(@Nullable String[] configured, @Nonnull String[] defaults) {
        String[] cleaned = cleanArray(configured);
        return cleaned.length == 0 ? Arrays.copyOf(defaults, defaults.length) : cleaned;
    }

    @Nonnull
    private static int[] resolveTargetSlotIndices(@Nonnull String[] slots, @Nonnull BuilderSupport support) {
        int[] indices = new int[slots.length];
        for (int i = 0; i < slots.length; i++) {
            String slot = slots[i];
            try {
                indices[i] = support.getTargetSlot(slot);
            } catch (Exception ignored) {
                indices[i] = Integer.MIN_VALUE;
            }
        }
        return indices;
    }

    @Nullable
    private static StdScope snapshotScope(@Nullable Scope scope) {
        if (scope == null) {
            return null;
        }
        if (scope instanceof StdScope stdScope) {
            return StdScope.copyOf(stdScope);
        }
        return new StdScope(scope);
    }

    @Nonnull
    private static String[] cleanArray(@Nullable String[] values) {
        if (values == null || values.length == 0) {
            return new String[0];
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isBlank()) {
                unique.add(trimmed);
            }
        }
        return unique.toArray(new String[0]);
    }

    @Nonnull
    private static String formatProbe(@Nullable String probe) {
        if (probe == null) {
            return "<empty>";
        }
        String trimmed = probe.trim();
        return trimmed.isBlank() ? "<empty>" : trimmed;
    }

    @Nonnull
    private static String formatNumber(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    @Nonnull
    private static String formatNumberArray(@Nonnull double[] values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(formatNumber(values[i]));
        }
        out.append("]");
        return out.toString();
    }

    @Nonnull
    private static String formatVector(double x, double y, double z) {
        return "[" + formatNumber(x) + ", " + formatNumber(y) + ", " + formatNumber(z) + "]";
    }
}
