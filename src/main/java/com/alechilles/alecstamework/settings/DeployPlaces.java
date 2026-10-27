package com.alechilles.alecstamework.settings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Where companions may come out into a world, owned by `/tw settings`. Coming out is anything that
 * puts an owned companion into a world: a release from a capture item, a summon, a recall, a
 * recover, a revive, a follow through a world change, a coop release, a tame or a birth. Companions
 * in items and storage still move, sync and trade everywhere; companions already out stay.
 *
 * <p>A place is a world name, a server id, or {@code server/world}. Server ids are the network
 * store's {@code holderId}, so one list can serve every server of a network; without a network
 * store only world names match. Matching ignores case.</p>
 *
 * @param mode   whether the places are the exceptions or the only allowed ones
 * @param places the places, as the admin wrote them
 */
public record DeployPlaces(@Nonnull Mode mode, @Nonnull List<String> places) {
    /** Everywhere, the default. */
    public static final DeployPlaces DEFAULT = new DeployPlaces(Mode.EVERYWHERE_EXCEPT, List.of());

    public enum Mode {
        /** Everywhere except the listed places. */
        EVERYWHERE_EXCEPT("EverywhereExcept"),
        /** Only in the listed places. */
        ONLY_LISTED("OnlyListed");

        private final String configValue;

        Mode(String configValue) {
            this.configValue = configValue;
        }

        @Nonnull
        public String configValue() {
            return configValue;
        }

        /** The mode for a config value; unknown or missing values read as {@link #EVERYWHERE_EXCEPT}. */
        @Nonnull
        public static Mode fromConfigValue(@Nullable String value) {
            for (Mode mode : values()) {
                if (mode.configValue.equalsIgnoreCase(value == null ? "" : value.trim())) {
                    return mode;
                }
            }
            return EVERYWHERE_EXCEPT;
        }
    }

    public DeployPlaces {
        mode = mode == null ? Mode.EVERYWHERE_EXCEPT : mode;
        places = clean(places);
    }

    /**
     * Whether a companion may come out in {@code world} on the server named {@code serverId} (null
     * without a network store).
     */
    public boolean allows(@Nullable String serverId, @Nullable String world) {
        boolean listed = listed(serverId, world);
        return mode == Mode.ONLY_LISTED ? listed : !listed;
    }

    private boolean listed(@Nullable String serverId, @Nullable String world) {
        String w = world == null ? "" : world.trim();
        String s = serverId == null ? "" : serverId.trim();
        for (String place : places) {
            int slash = place.indexOf('/');
            boolean match = slash < 0
                    ? place.equalsIgnoreCase(w) || !s.isEmpty() && place.equalsIgnoreCase(s)
                    : !s.isEmpty() && place.substring(0, slash).trim().equalsIgnoreCase(s)
                            && place.substring(slash + 1).trim().equalsIgnoreCase(w);
            if (match) {
                return true;
            }
        }
        return false;
    }

    /** Parses the comma-separated place list a settings text field holds. */
    @Nonnull
    public static List<String> parsePlaces(@Nullable String raw) {
        return raw == null || raw.isBlank() ? List.of() : clean(List.of(raw.split(",")));
    }

    /** Formats a place list for a settings text field. */
    @Nonnull
    public static String formatPlaces(@Nonnull List<String> places) {
        return String.join(", ", places);
    }

    @Nonnull
    private static List<String> clean(@Nullable List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String place : raw) {
            if (place != null && !place.isBlank() && seen.add(place.trim().toLowerCase(Locale.ROOT))) {
                out.add(place.trim());
            }
        }
        return List.copyOf(out);
    }
}
