package com.alechilles.alecstamework.runtime.activation;

import java.util.List;

/**
 * Stable identity for one Tamework runtime module.
 *
 * <p>The value is deliberately independent of Hytale registration objects. A
 * module can therefore be named by startup evidence before any ECS system or
 * worker is constructed.</p>
 */
public final class TameworkRuntimeModule implements Comparable<TameworkRuntimeModule> {
    public static final TameworkRuntimeModule CORE_OWNERSHIP = of("core-ownership");
    public static final TameworkRuntimeModule INTERACTIONS = of("interactions");
    public static final TameworkRuntimeModule CAPTURE = of("capture");
    public static final TameworkRuntimeModule NAMING_ITEMS = of("naming-items");
    public static final TameworkRuntimeModule SPAWNER_ITEMS = of("spawner-items");
    public static final TameworkRuntimeModule COMMAND_ITEMS = of("command-items");
    public static final TameworkRuntimeModule MOUNTS = of("mounts");
    public static final TameworkRuntimeModule AVATAR_FLIGHT = of("avatar-flight");
    public static final TameworkRuntimeModule COMPANION_MOVEMENT = of("companion-movement");
    public static final TameworkRuntimeModule ATTACHMENTS = of("attachments");
    public static final TameworkRuntimeModule NEEDS = of("needs");
    public static final TameworkRuntimeModule HAPPINESS = of("happiness");
    public static final TameworkRuntimeModule FOOD = of("food");
    public static final TameworkRuntimeModule BREEDING = of("breeding");
    public static final TameworkRuntimeModule LEVELING = of("leveling");
    public static final TameworkRuntimeModule TRAITS = of("traits");
    public static final TameworkRuntimeModule TALENTS = of("talents");
    public static final TameworkRuntimeModule COOPS = of("coops");
    public static final TameworkRuntimeModule SCARECROWS = of("scarecrows");
    public static final TameworkRuntimeModule DAMAGE_PROJECTILES = of("damage-projectiles");
    public static final TameworkRuntimeModule GENERIC_PERSISTENCE = of("generic-persistence");
    public static final TameworkRuntimeModule DORMANT_PERSISTENCE = of("dormant-persistence");
    public static final TameworkRuntimeModule BONDED_PERSISTENCE = of("bonded-persistence");
    public static final TameworkRuntimeModule HSTATS = of("hstats");
    public static final TameworkRuntimeModule DEBUG_SELF_TEST = of("debug-self-test");

    private static final List<TameworkRuntimeModule> STANDARD_MODULES = List.of(
            CORE_OWNERSHIP,
            INTERACTIONS,
            CAPTURE,
            NAMING_ITEMS,
            SPAWNER_ITEMS,
            COMMAND_ITEMS,
            MOUNTS,
            AVATAR_FLIGHT,
            COMPANION_MOVEMENT,
            ATTACHMENTS,
            NEEDS,
            HAPPINESS,
            FOOD,
            BREEDING,
            LEVELING,
            TRAITS,
            TALENTS,
            COOPS,
            SCARECROWS,
            DAMAGE_PROJECTILES,
            GENERIC_PERSISTENCE,
            DORMANT_PERSISTENCE,
            BONDED_PERSISTENCE,
            HSTATS,
            DEBUG_SELF_TEST
    );

    private final String id;

    private TameworkRuntimeModule(String id) {
        this.id = requireId(id);
    }

    /** Returns a stable module value for the supplied ID. */
    public static TameworkRuntimeModule of(String id) {
        return new TameworkRuntimeModule(id);
    }

    /** Returns the immutable built-in module list in declaration order. */
    public static List<TameworkRuntimeModule> standardModules() {
        return STANDARD_MODULES;
    }

    /** Returns the stable identifier used in plans and topology fingerprints. */
    public String id() {
        return id;
    }


    @Override
    public int compareTo(TameworkRuntimeModule other) {
        return id.compareTo(other.id);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof TameworkRuntimeModule module
                && id.equals(module.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id;
    }

    private static String requireId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Runtime module ID is required");
        }
        return value.trim();
    }
}
