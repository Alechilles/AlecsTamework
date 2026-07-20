package com.alechilles.alecstamework.persistence.incidents;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Stable administrator-facing keys for the supported persistence feature circuits. */
public final class PersistenceFeatureCircuitCatalog {
    private static final LinkedHashMap<String, PersistenceDomain> DOMAINS = domains();

    private PersistenceFeatureCircuitCatalog() {
    }

    @Nonnull
    public static List<String> keys() {
        return List.copyOf(DOMAINS.keySet());
    }

    @Nullable
    public static PersistenceDomain resolve(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        return DOMAINS.get(normalize(raw));
    }

    @Nonnull
    public static String key(@Nonnull PersistenceDomain domain) {
        for (Map.Entry<String, PersistenceDomain> entry : DOMAINS.entrySet()) {
            if (entry.getValue() == domain) return entry.getKey();
        }
        return domain.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static LinkedHashMap<String, PersistenceDomain> domains() {
        LinkedHashMap<String, PersistenceDomain> domains = new LinkedHashMap<>();
        domains.put("all", PersistenceDomain.ALL_PERSISTENCE);
        domains.put("taming-ownership", PersistenceDomain.TAMING_OWNERSHIP);
        domains.put("admin-tamed-spawn", PersistenceDomain.ADMIN_TAMED_SPAWN);
        domains.put("capture-intake", PersistenceDomain.CAPTURE_INTAKE);
        domains.put("capture-release", PersistenceDomain.CAPTURE_RELEASE);
        domains.put("coop-intake", PersistenceDomain.MANAGED_COOP_INTAKE);
        domains.put("coop-release", PersistenceDomain.MANAGED_COOP_RELEASE);
        domains.put("coop-automation", PersistenceDomain.MANAGED_COOP_AUTOMATION);
        domains.put("breeding-pairing", PersistenceDomain.BREEDING_PAIRING);
        domains.put("breeding-birth", PersistenceDomain.BREEDING_BIRTH);
        domains.put("recall-relocation", PersistenceDomain.RECALL_RELOCATION);
        domains.put("death-lost-recovery", PersistenceDomain.DEATH_LOST_RECOVERY);
        domains.put("automatic-recovery", PersistenceDomain.AUTOMATIC_SCOPED_RECOVERY);
        return domains;
    }

    private static String normalize(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
