package com.alechilles.alecstamework.integration.questlinesclaims;

import com.alechilles.alecstamework.integration.claims.ClaimLookupResult;
import com.alechilles.alecstamework.integration.claims.ClaimModDamageProvider;
import com.alechilles.alecstamework.integration.claims.ClaimModSelector;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.PluginState;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * QuestLines Claims, read by reflection so Tamework loads and runs without it. Its classes are
 * bound once per plugin instance with the plugin's own class loader, and bound again when the
 * plugin is reloaded.
 *
 * <p>Verified against QuestLines Claims 1.8.0: {@code QuestLinesClaimsPlugin.getInstance().getApi()},
 * {@code QuestLinesClaimsAPI.getClaimAtBlock(String, int, int)}, {@code PlayerClaim.getClaimId()},
 * {@code PlayerClaim.getChunks()} and
 * {@code QuestLinesClaimsAPI.canPlayerAct(PlayerRef, String, Vector3i, RegionFlags)}. The damage
 * rule is the {@code PVE} flag, which QuestLines describes as hurting creatures.</p>
 *
 * <p>Call on the world thread. The attacker's {@link PlayerRef} comes from the universe's player
 * list by UUID. QuestLines may read the attacker's components inside {@code canPlayerAct} (for its
 * permission groups), so the damage check runs only on the thread of the world the attacker is in
 * and reports an error, which lets the damage through, anywhere else.</p>
 */
public final class QuestLinesClaimsProvider implements ClaimModDamageProvider {
    private static final System.Logger LOGGER = System.getLogger(QuestLinesClaimsProvider.class.getName());
    private static final String NOT_ENABLED = "QuestLines Claims is not installed or not enabled.";

    private final PluginIdentifier pluginId = PluginIdentifier.fromString(ClaimModSelector.QUESTLINES_CLAIMS_PLUGIN_ID);
    private final AtomicBoolean warned = new AtomicBoolean();
    private volatile Bound bound;

    @Nullable
    @Override
    public String unavailableReason() {
        return unavailableReason(current());
    }

    @Nonnull
    @Override
    public ClaimLookupResult lookupClaim(@Nonnull String worldName, @Nonnull Vector3d position) {
        Bound current = current();
        Binding binding = current == null ? null : current.binding();
        if (binding == null) {
            return ClaimLookupResult.unavailable(unavailableReason(current));
        }
        try {
            Object api = binding.api();
            if (api == null) {
                return ClaimLookupResult.error("QuestLines Claims API is not ready.");
            }
            Object claim = binding.getClaimAtBlock().invoke(api, worldName,
                    floor(position.x), floor(position.z));
            if (claim == null) {
                return ClaimLookupResult.noClaim();
            }
            Object claimId = binding.getClaimId().invoke(claim);
            Object chunks = binding.getChunks().invoke(claim);
            int chunkCount = chunks instanceof Collection<?> collection ? collection.size() : 0;
            return ClaimLookupResult.found("questlines:" + claimId, chunkCount);
        } catch (InvocationTargetException thrown) {
            return ClaimLookupResult.error("QuestLines Claims claim lookup failed: " + message(thrown.getCause()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failed) {
            return ClaimLookupResult.error("QuestLines Claims claim lookup failed: " + message(failed));
        }
    }

    @Nonnull
    @Override
    public DamageResult evaluateTamedDamage(@Nonnull String worldName,
                                            @Nonnull Vector3d position,
                                            @Nonnull UUID attackerPlayerUuid) {
        Bound current = current();
        Binding binding = current == null ? null : current.binding();
        if (binding == null) {
            return new DamageResult(DamageResult.Status.UNAVAILABLE, unavailableReason(current));
        }
        PlayerRef attacker = onlinePlayer(attackerPlayerUuid);
        if (attacker == null) {
            return new DamageResult(DamageResult.Status.ERROR, "The attacking player is not online.");
        }
        if (!onAttackerWorldThread(attacker, worldName)) {
            return new DamageResult(DamageResult.Status.ERROR,
                    "The attacking player is not in world " + worldName + " on its thread.");
        }
        try {
            Object api = binding.api();
            if (api == null) {
                return new DamageResult(DamageResult.Status.ERROR, "QuestLines Claims API is not ready.");
            }
            Object blockPosition = binding.newVector3i().newInstance(
                    floor(position.x), floor(position.y), floor(position.z));
            Object allowed = binding.canPlayerAct().invoke(api, attacker, worldName, blockPosition, binding.pveFlag());
            return Boolean.TRUE.equals(allowed)
                    ? new DamageResult(DamageResult.Status.ALLOWED, null)
                    : new DamageResult(DamageResult.Status.DENIED, null);
        } catch (InvocationTargetException thrown) {
            return new DamageResult(DamageResult.Status.ERROR,
                    "QuestLines Claims damage check failed: " + message(thrown.getCause()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failed) {
            return new DamageResult(DamageResult.Status.ERROR,
                    "QuestLines Claims damage check failed: " + message(failed));
        }
    }

    /** Drops the bound plugin instance so a stopped server keeps no reference to it. */
    public void close() {
        bound = null;
    }

    /** The binding for the enabled plugin instance, or null when QuestLines is not enabled. */
    @Nullable
    private Bound current() {
        PluginBase plugin = enabledPlugin();
        return plugin == null ? null : bind(plugin);
    }

    /** Why {@code current} cannot answer, or null when it can. */
    @Nullable
    private static String unavailableReason(@Nullable Bound current) {
        if (current == null) {
            return NOT_ENABLED;
        }
        return current.binding() != null ? null : current.failure();
    }

    @Nonnull
    private Bound bind(@Nonnull PluginBase plugin) {
        Bound current = bound;
        if (current == null || current.plugin() != plugin) {
            current = bindSafely(plugin);
            bound = current;
        }
        return current;
    }

    @Nullable
    private PluginBase enabledPlugin() {
        try {
            PluginManager manager = PluginManager.get();
            PluginBase plugin = manager == null ? null : manager.getPlugin(pluginId);
            return plugin != null && plugin.getState() == PluginState.ENABLED ? plugin : null;
        } catch (RuntimeException | LinkageError failed) {
            return null;
        }
    }

    @Nonnull
    private Bound bindSafely(@Nonnull PluginBase plugin) {
        try {
            return new Bound(plugin, Binding.bind(plugin.getClass().getClassLoader()), null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failed) {
            if (warned.compareAndSet(false, true)) {
                LOGGER.log(System.Logger.Level.WARNING, "This version of QuestLines Claims is not supported by"
                        + " Tamework's claim integration; claim checks report it unavailable.", failed);
            }
            return new Bound(plugin, null, "This version of QuestLines Claims is not supported: " + message(failed));
        }
    }

    @Nullable
    private static PlayerRef onlinePlayer(@Nonnull UUID playerUuid) {
        Universe universe = Universe.get();
        return universe == null ? null : universe.getPlayer(playerUuid);
    }

    /** True when the attacker is in {@code worldName} and this is that world's thread. */
    private static boolean onAttackerWorldThread(@Nonnull PlayerRef attacker, @Nonnull String worldName) {
        UUID worldUuid = attacker.getWorldUuid();
        Universe universe = Universe.get();
        World world = worldUuid == null || universe == null ? null : universe.getWorld(worldUuid);
        return world != null && worldName.equals(world.getName()) && world.isInThread();
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    @Nonnull
    private static String message(@Nullable Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String detail = throwable.getMessage();
        return detail == null || detail.isBlank() ? throwable.getClass().getSimpleName() : detail;
    }

    private record Bound(@Nonnull PluginBase plugin, @Nullable Binding binding, @Nullable String failure) {
    }

    /** The QuestLines classes and methods, loaded with the plugin's class loader. */
    private record Binding(@Nonnull Method getInstance,
                           @Nonnull Method getApi,
                           @Nonnull Method getClaimAtBlock,
                           @Nonnull Method getClaimId,
                           @Nonnull Method getChunks,
                           @Nonnull Method canPlayerAct,
                           @Nonnull Constructor<?> newVector3i,
                           @Nonnull Object pveFlag) {

        @Nonnull
        static Binding bind(@Nullable ClassLoader loader) throws ReflectiveOperationException {
            Class<?> plugin = Class.forName("net.evilcraft.questlinesclaims.QuestLinesClaimsPlugin", false, loader);
            Class<?> api = Class.forName("net.evilcraft.questlinesclaims.api.QuestLinesClaimsAPI", false, loader);
            Class<?> claim = Class.forName("net.evilcraft.questlinesclaims.data.PlayerClaim", false, loader);
            Class<?> flags = Class.forName("net.evilcraft.questlinesclaims.data.RegionFlags", false, loader);
            Class<?> vector3i = Class.forName("org.joml.Vector3i", false, loader);
            return new Binding(
                    plugin.getMethod("getInstance"),
                    plugin.getMethod("getApi"),
                    api.getMethod("getClaimAtBlock", String.class, int.class, int.class),
                    claim.getMethod("getClaimId"),
                    claim.getMethod("getChunks"),
                    api.getMethod("canPlayerAct", PlayerRef.class, String.class, vector3i, flags),
                    vector3i.getConstructor(int.class, int.class, int.class),
                    enumConstant(flags, "PVE")
            );
        }

        @Nullable
        Object api() throws ReflectiveOperationException {
            Object instance = getInstance.invoke(null);
            return instance == null ? null : getApi.invoke(instance);
        }

        @Nonnull
        private static Object enumConstant(@Nonnull Class<?> type, @Nonnull String name) throws NoSuchFieldException {
            Object[] constants = type.getEnumConstants();
            if (constants != null) {
                for (Object constant : constants) {
                    if (((Enum<?>) constant).name().equals(name)) {
                        return constant;
                    }
                }
            }
            throw new NoSuchFieldException(type.getName() + "." + name);
        }
    }
}
