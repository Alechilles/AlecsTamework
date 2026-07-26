package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Maps durable full state to panel fields without consulting a live NPC. */
public final class BondedCompanionSnapshotPresentationMapper {
    private final RolePresentationResolver roles;

    public BondedCompanionSnapshotPresentationMapper(
            @Nonnull RolePresentationResolver roles
    ) {
        this.roles = Objects.requireNonNull(roles, "roles");
    }

    /** Resolves name, species, gender, and useful panel data from stored state. */
    @Nonnull
    public Presentation map(@Nonnull BondedCompanionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        CoopResidentStateSnapshot state = snapshot.fullState();
        RolePresentation role = roles.resolve(state.roleId());
        if (role == null) {
            role = new RolePresentation(null, null, null, Map.of());
        }
        String snapshotName = state.npcName() == null
                ? null : state.npcName().getName();
        String snapshotGender = state.lifeStage() == null
                ? null : state.lifeStage().getGender();
        LinkedHashMap<String, String> data = new LinkedHashMap<>(role.data());
        put(data, "roleId", state.roleId());
        put(data, "level", state.leveling() == null
                ? null : Integer.toString(state.leveling().getLevel()));
        put(data, "healthPercent", state.healthPercent() == null
                ? null : Double.toString(state.healthPercent()));
        put(data, "happiness", state.happiness() == null
                ? null : Double.toString(state.happiness().getValue()));
        return new Presentation(
                first(snapshotName, role.displayName()),
                role.species(),
                first(snapshotGender, role.gender()),
                data
        );
    }

    private static void put(Map<String, String> target, String key, String value) {
        String normalized = normalize(value);
        if (normalized != null) {
            target.put(key, normalized);
        }
    }

    private static String first(String primary, String fallback) {
        String normalized = normalize(primary);
        return normalized != null ? normalized : normalize(fallback);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Supplies configured role presentation without requiring world state. */
    @FunctionalInterface
    public interface RolePresentationResolver {
        @Nullable RolePresentation resolve(@Nullable String roleId);
    }

    /** Configured fallback presentation for one resolved role. */
    public record RolePresentation(
            @Nullable String displayName,
            @Nullable String species,
            @Nullable String gender,
            @Nonnull Map<String, String> data
    ) {
        public RolePresentation {
            displayName = normalize(displayName);
            species = normalize(species);
            gender = normalize(gender);
            data = Map.copyOf(Objects.requireNonNull(data, "data"));
        }
    }

    /** Panel-facing fields derived entirely from durable data and role config. */
    public record Presentation(
            @Nullable String displayName,
            @Nullable String species,
            @Nullable String gender,
            @Nonnull Map<String, String> data
    ) {
        public Presentation {
            displayName = normalize(displayName);
            species = normalize(species);
            gender = normalize(gender);
            data = Map.copyOf(Objects.requireNonNull(data, "data"));
        }
    }
}
