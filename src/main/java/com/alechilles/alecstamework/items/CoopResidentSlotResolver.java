package com.alechilles.alecstamework.items;

import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.server.core.entity.reference.PersistentRef;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Reflection helpers for reading coop resident slots from vanilla {@link CoopBlock} state.
 */
final class CoopResidentSlotResolver {
    @Nullable
    private static final Field RESIDENTS_FIELD = resolveResidentsField();

    private CoopResidentSlotResolver() {
    }

    static int resolveMostRecentResidentSlot(@Nullable CoopBlock coopBlock) {
        List<?> residents = readResidents(coopBlock);
        if (residents == null || residents.isEmpty()) {
            return -1;
        }
        return residents.size() - 1;
    }

    static int resolveResidentSlotByUuid(@Nullable CoopBlock coopBlock, @Nullable UUID npcUuid) {
        if (npcUuid == null) {
            return -1;
        }
        List<?> residents = readResidents(coopBlock);
        if (residents == null || residents.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < residents.size(); i++) {
            PersistentRef persistentRef = resolvePersistentRef(residents.get(i));
            if (persistentRef == null || persistentRef.getUuid() == null) {
                continue;
            }
            if (npcUuid.equals(persistentRef.getUuid())) {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static List<?> readResidents(@Nullable CoopBlock coopBlock) {
        if (coopBlock == null || RESIDENTS_FIELD == null) {
            return null;
        }
        try {
            Object value = RESIDENTS_FIELD.get(coopBlock);
            if (value instanceof List<?> list) {
                return list;
            }
        } catch (IllegalAccessException ignored) {
            // Return null so callers can fall back to non-slot matching.
        }
        return null;
    }

    @Nullable
    private static PersistentRef resolvePersistentRef(@Nullable Object resident) {
        if (!(resident instanceof CoopBlock.CoopResident coopResident)) {
            return null;
        }
        try {
            return coopResident.getPersistentRef();
        } catch (Exception | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    private static Field resolveResidentsField() {
        try {
            Field field = CoopBlock.class.getDeclaredField("residents");
            field.setAccessible(true);
            return field;
        } catch (Exception | LinkageError ignored) {
            return null;
        }
    }
}
