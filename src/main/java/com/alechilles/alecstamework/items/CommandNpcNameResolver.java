package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.npc.NpcDisplayNameComponentService;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves NPC-facing names for command item UI and metadata.
 * <p>
 * Resolution order is:
 * 1) explicit display name components (custom/player-facing),
 * 2) translated name key,
 * 3) role id.
 */
final class CommandNpcNameResolver {
    private final TranslationRegistry translationRegistry;

    CommandNpcNameResolver() {
        this(null);
    }

    CommandNpcNameResolver(TranslationRegistry translationRegistry) {
        this.translationRegistry = translationRegistry;
    }

    String resolveNpcDisplayName(Ref<EntityStore> npcRef, Store<EntityStore> store, NPCEntity npc) {
        if (npc == null) {
            return "NPC";
        }
        String componentDisplayName = resolveNpcDisplayNameFromComponents(npcRef, store);
        if (componentDisplayName != null && !componentDisplayName.isBlank()) {
            return componentDisplayName;
        }
        String displayName = npc.getLegacyDisplayName();
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        String nameKey = resolveNpcNameKey(npc);
        if (nameKey != null && !nameKey.isBlank()) {
            String translated = translateNpcNameKey(nameKey);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
        }
        String roleId = resolveNpcRoleId(npc);
        if (roleId != null && !roleId.isBlank()) {
            return roleId;
        }
        return "NPC";
    }

    String resolveCachedUnloadedDisplayName(LinkedNpcRecord record) {
        if (record == null) {
            return null;
        }
        if (record.cachedDisplayName != null && !record.cachedDisplayName.isBlank()) {
            String translated = translateSnapshotName(record.cachedDisplayName, record.cachedNameKey, record.cachedRoleId);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
            return record.cachedDisplayName;
        }
        if (record.cachedNameKey != null && !record.cachedNameKey.isBlank()) {
            String translated = translateNpcNameKey(record.cachedNameKey);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
            return record.cachedNameKey;
        }
        if (record.cachedRoleId != null && !record.cachedRoleId.isBlank()) {
            String translated = translateNpcNameKey(record.cachedRoleId);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
            return record.cachedRoleId;
        }
        return null;
    }

    String resolveSnapshotDisplayName(String snapshotDisplayName, String roleId) {
        String translated = translateSnapshotName(snapshotDisplayName, null, roleId);
        if (translated != null && !translated.isBlank()) {
            return translated;
        }
        if (snapshotDisplayName != null && !snapshotDisplayName.isBlank()) {
            return snapshotDisplayName;
        }
        if (roleId != null && !roleId.isBlank()) {
            String translatedRole = translateNpcNameKey(roleId);
            if (translatedRole != null && !translatedRole.isBlank()) {
                return translatedRole;
            }
            return roleId;
        }
        return null;
    }

    String resolveNpcDisplayNameFromComponents(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        ComponentType<EntityStore, TameworkNpcNameComponent> nameType = TameworkNpcNameComponent.getComponentType();
        if (nameType != null) {
            TameworkNpcNameComponent nameComponent = store.getComponent(npcRef, nameType);
            if (nameComponent != null && nameComponent.getName() != null && !nameComponent.getName().isBlank()) {
                return nameComponent.getName();
            }
        }
        return NpcDisplayNameComponentService.resolvePersistentOrRuntimeName(npcRef, store);
    }

    String resolveNpcNameKey(NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        String roleParamNameKey = resolveRoleNameKeyFromParams(npc.getRole());
        if (roleParamNameKey != null && !roleParamNameKey.isBlank()) {
            return roleParamNameKey;
        }
        String roleId = resolveNpcRoleId(npc);
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        if (looksLikeTranslationKey(roleId)) {
            return roleId;
        }
        return "server.npcRole." + roleId + ".name";
    }

    String resolveNpcRoleId(NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        String roleId = readStringGetter(npc, "getRoleId");
        if (roleId != null && !roleId.isBlank()) {
            return roleId;
        }
        Role role = npc.getRole();
        if (role == null) {
            return null;
        }
        return readStringGetter(role, "getId", "getRoleId", "id");
    }

    private String resolveRoleNameKeyFromParams(Role role) {
        if (role == null) {
            return null;
        }
        Object markedSupport = invokeObjectGetter(role, "getMarkedEntitySupport");
        if (markedSupport == null) {
            return null;
        }
        Object entitySupport = invokeObjectGetter(markedSupport, "getEntitySupport");
        if (entitySupport == null) {
            return null;
        }
        Object sensorScope = invokeObjectGetter(entitySupport, "getSensorScope");
        return readScopeStringParam(
                sensorScope,
                "NameTranslationKey",
                "RoleNameTranslationKey",
                "NameKey",
                "RoleNameKey",
                "NpcNameKey"
        );
    }

    private boolean looksLikeTranslationKey(String value) {
        return value != null && !value.isBlank() && value.indexOf('.') >= 0;
    }

    private String translateNpcNameKey(String nameKey) {
        if (nameKey == null || nameKey.isBlank()) {
            return null;
        }
        TranslationRegistry registry = translationRegistry;
        if (registry == null) {
            Tamework instance = Tamework.getInstance();
            registry = instance != null ? instance.getTranslationRegistry() : null;
        }
        if (registry == null) {
            return null;
        }
        for (String candidate : buildNameKeyCandidates(nameKey)) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String translated = registry.get(candidate);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
        }
        return null;
    }

    private List<String> buildNameKeyCandidates(String nameKey) {
        ArrayList<String> candidates = new ArrayList<>(8);
        addCandidate(candidates, nameKey);
        if (!nameKey.contains(".")) {
            addCandidate(candidates, "npcRole." + nameKey + ".name");
            addCandidate(candidates, "server.npcRole." + nameKey + ".name");
            addCandidate(candidates, "npcRoles." + nameKey + ".name");
            addCandidate(candidates, "server.npcRoles." + nameKey + ".name");
            return candidates;
        }
        if (nameKey.startsWith("server.")) {
            addCandidate(candidates, nameKey.substring("server.".length()));
        } else {
            addCandidate(candidates, "server." + nameKey);
        }
        String canonical = nameKey.replace("npcRoles.", "npcRole.").replace("server.npcRoles.", "server.npcRole.");
        addCandidate(candidates, canonical);
        if (canonical.startsWith("server.")) {
            addCandidate(candidates, canonical.substring("server.".length()));
        } else {
            addCandidate(candidates, "server." + canonical);
        }
        if (canonical.endsWith(".title")) {
            addCandidate(candidates, canonical.substring(0, canonical.length() - ".title".length()) + ".name");
        }
        return candidates;
    }

    private void addCandidate(List<String> candidates, String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (!candidates.contains(key)) {
            candidates.add(key);
        }
    }

    private String translateSnapshotName(String snapshotDisplayName, String nameKey, String roleId) {
        if (snapshotDisplayName != null && !snapshotDisplayName.isBlank()) {
            if (looksLikeTranslationKey(snapshotDisplayName)) {
                String translated = translateNpcNameKey(snapshotDisplayName);
                if (translated != null && !translated.isBlank()) {
                    return translated;
                }
            }
            if (roleId != null && !roleId.isBlank() && snapshotDisplayName.equalsIgnoreCase(roleId)) {
                String translated = translateNpcNameKey(roleId);
                if (translated != null && !translated.isBlank()) {
                    return translated;
                }
            }
            if (nameKey != null && !nameKey.isBlank() && snapshotDisplayName.equalsIgnoreCase(nameKey)) {
                String translated = translateNpcNameKey(nameKey);
                if (translated != null && !translated.isBlank()) {
                    return translated;
                }
            }
        }
        if (nameKey != null && !nameKey.isBlank()) {
            String translated = translateNpcNameKey(nameKey);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
        }
        return null;
    }

    private static String readScopeStringParam(Object scope, String... paramNames) {
        if (scope == null || paramNames == null || paramNames.length == 0) {
            return null;
        }
        for (String paramName : paramNames) {
            if (paramName == null || paramName.isBlank()) {
                continue;
            }
            try {
                Method method = scope.getClass().getMethod("getStringParamOrNull", String.class);
                Object value = method.invoke(scope, paramName);
                if (value instanceof String text && !text.isBlank()) {
                    return text;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private static String readStringGetter(Object target, String... methodNames) {
        if (target == null || methodNames == null) {
            return null;
        }
        for (String methodName : methodNames) {
            String value = invokeStringGetter(target, methodName);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String invokeStringGetter(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static Object invokeObjectGetter(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
