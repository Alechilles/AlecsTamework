package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the pure evidence-to-authority half of managed physical-coop discovery. */
class ManagedCoopChunkScannerTest {

    @Test
    void reliableEvidenceResolvesExactContextsAndDeduplicatesDualComponentRows() throws Exception {
        TwCoopConfig chicken = config("chicken", "coop_chicken");
        ManagedCoopAuthorityEligibilityIndex eligibility =
                new ManagedCoopAuthorityEligibilityIndex();
        ManagedCoopChunkScanner scanner = scanner(
                Map.of("coop_chicken", chicken), eligibility);
        Vector3i mutableBlock = new Vector3i(1, 2, 3);
        ManagedCoopChunkScanner.CoopEvidence evidence = new ManagedCoopChunkScanner.CoopEvidence(
                " WORLD ", "block_chicken", "coop_chicken", mutableBlock, 4, null);
        mutableBlock.x = 99;

        ManagedCoopChunkScanner.ScanResult result = scanner.resolve(
                ManagedCoopChunkScanner.EvidenceRead.reliable(List.of(
                        evidence,
                        evidence,
                        new ManagedCoopChunkScanner.CoopEvidence(
                                "world", "unknown", "unknown", new Vector3i(9, 9, 9), 0, null))));

        assertTrue(result.reliable());
        assertEquals(1, result.contexts().size());
        assertEquals(new Vector3i(1, 2, 3), result.contexts().getFirst().block());
        assertEquals("world", result.contexts().getFirst().worldName());
        assertEquals(1, result.duplicateEvidence());
        assertEquals(1, result.rejectedEvidence());

        scanner.publishEligibility("world", result);
        assertTrue(eligibility.snapshot().contains(
                new ManagedCoopAuthorityKey("world", 1, 2, 3), "coop_chicken"));

        ManagedCoopChunkScanner.ScanResult unresolved = scanner.resolve(
                ManagedCoopChunkScanner.EvidenceRead.reliable(List.of(
                        new ManagedCoopChunkScanner.CoopEvidence(
                                "world", "unknown", "unknown",
                                new Vector3i(1, 2, 3), 0, null))));
        scanner.publishEligibility("world", unresolved);
        assertTrue(eligibility.snapshot().coopIds().isEmpty(),
                "a reliable unresolved/config-removed scan must revoke cleanup authority");
    }

    @Test
    void conflictingCoopIdsAtOneAuthorityFailTheWholeScan() throws Exception {
        ManagedCoopAuthorityEligibilityIndex eligibility =
                new ManagedCoopAuthorityEligibilityIndex();
        eligibility.replaceWorld("world", List.of(
                new ManagedCoopAuthorityEligibilityIndex.AuthorityEvidence(
                        new ManagedCoopAuthorityKey("world", 1, 2, 3), "coop_chicken")));
        ManagedCoopChunkScanner scanner = scanner(Map.of(
                "coop_chicken", config("chicken", "coop_chicken"),
                "coop_duck", config("duck", "coop_duck")), eligibility);
        Vector3i block = new Vector3i(1, 2, 3);

        ManagedCoopChunkScanner.ScanResult result = scanner.resolve(
                ManagedCoopChunkScanner.EvidenceRead.reliable(List.of(
                        new ManagedCoopChunkScanner.CoopEvidence(
                                "world", null, "coop_chicken", block, 0, null),
                        new ManagedCoopChunkScanner.CoopEvidence(
                                "world", null, "coop_duck", block, 0, null))));

        assertEquals(ManagedCoopChunkScanner.ScanStatus.FAILED, result.status());
        assertTrue(result.contexts().isEmpty());
        assertEquals("managed_coop_scan_authority_config_conflict", result.detail());

        scanner.publishEligibility("world", result);
        assertTrue(eligibility.snapshot().coopIds().isEmpty(),
                "failed authority resolution must be non-destructive until a reliable rescan");
    }

    private static ManagedCoopChunkScanner scanner(Map<String, TwCoopConfig> configs) {
        return scanner(configs, new ManagedCoopAuthorityEligibilityIndex());
    }

    private static ManagedCoopChunkScanner scanner(
            Map<String, TwCoopConfig> configs,
            ManagedCoopAuthorityEligibilityIndex eligibility) {
        ManagedCoopAuthorityResolver resolver = new ManagedCoopAuthorityResolver(
                new ManagedCoopAuthorityResolver.ConfigLookup() {
                    @Override
                    public TwCoopConfig forBlockType(String blockTypeId) {
                        return configs.get(blockTypeId);
                    }

                    @Override
                    public TwCoopConfig forCoop(String coopId) {
                        return configs.get(coopId);
                    }
                });
        return new ManagedCoopChunkScanner(
                resolver,
                (store, world) -> ManagedCoopChunkScanner.EvidenceRead.reliable(List.of()),
                eligibility);
    }

    private static TwCoopConfig config(String id, String coopId) throws Exception {
        var constructor = TwCoopConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwCoopConfig config = constructor.newInstance();
        set(config, "id", id);
        set(config, "enabled", true);
        set(config, "coopId", coopId);
        set(config.getIdentityRules(), "preserveUUID", false);
        return config;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
