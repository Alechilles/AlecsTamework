package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Canonicalizes command spawn attachment overrides against the spawned role model. */
final class NpcSpawnAttachmentResolutionService {
    @Nullable
    Resolution resolve(Ref<EntityStore> npcRef, Store<EntityStore> store,
                       @Nullable Map<String, String> requested) {
        if (requested == null || requested.isEmpty()) return null;
        ModelAsset model = CompanionModelAttachmentService.resolveModelAsset(npcRef, store);
        Map<String, Set<String>> options =
                CompanionModelAttachmentService.resolveAttachmentOptionIds(model);
        if (options.isEmpty()) return new Resolution(Map.of(), List.of("role has no attachment sets"));
        LinkedHashMap<String, String> applied = new LinkedHashMap<>();
        ArrayList<String> invalid = new ArrayList<>();
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            String set = canonical(options.keySet(), entry.getKey());
            String value = set == null ? null : canonical(options.get(set), entry.getValue());
            if (set == null || value == null) invalid.add(entry.getKey() + ":" + entry.getValue());
            else applied.put(set, value);
        }
        return new Resolution(applied, invalid);
    }

    @Nullable
    private static String canonical(@Nullable Iterable<String> values, @Nullable String requested) {
        if (values == null || requested == null || requested.isBlank()) return null;
        String key = token(requested);
        for (String value : values) {
            if (value != null && !value.isBlank() && token(value).equals(key)) return value;
        }
        return null;
    }

    @Nonnull
    private static String token(@Nonnull String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isLetterOrDigit(current)) result.append(Character.toLowerCase(current));
        }
        return result.toString();
    }

    record Resolution(Map<String, String> applied, List<String> invalid) {
        Resolution {
            applied = Map.copyOf(applied);
            invalid = List.copyOf(invalid);
        }
    }
}
