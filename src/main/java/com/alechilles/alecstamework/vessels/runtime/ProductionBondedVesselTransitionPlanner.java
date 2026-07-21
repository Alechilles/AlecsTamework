package com.alechilles.alecstamework.vessels.runtime;

import com.alechilles.alecstamework.api.BondedVesselMode;
import com.alechilles.alecstamework.api.BondedVesselProjectionStatus;
import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.api.BondedVesselTransition;
import com.alechilles.alecstamework.api.BondedVesselTransitionRequest;
import com.alechilles.alecstamework.api.SpawnerVesselConfigView;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.vessels.BondedVesselTransitionPlanner;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Production planner that freezes one revision-pinned spawner-vessel policy into the journal. */
public final class ProductionBondedVesselTransitionPlanner implements BondedVesselTransitionPlanner {
    private final ConfigResolver configs;
    private final BondedVesselItemFingerprintCodec fingerprints;

    public ProductionBondedVesselTransitionPlanner(@Nonnull ConfigResolver configs,
                                                   @Nonnull BondedVesselItemFingerprintCodec fingerprints) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.fingerprints = Objects.requireNonNull(fingerprints, "fingerprints");
    }

    @Nonnull
    @Override
    public Plan plan(@Nonnull BondedVesselBindingRecord binding,
                     @Nonnull BondedVesselTransitionRequest request,
                     long nowMs) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(request, "request");
        SpawnerVesselConfigView config = configs.resolve(binding.configId(), binding.configRevision())
                .orElseThrow(() -> new IllegalArgumentException("bonded-vessel-config-revision-unavailable"));
        if (!config.configId().equals(binding.configId())
                || config.configRevision() != binding.configRevision()) {
            throw new IllegalArgumentException("bonded-vessel-config-revision-mismatch");
        }
        if (config.mode() != BondedVesselMode.BONDED) {
            throw new IllegalArgumentException("bonded-vessel-config-not-bonded");
        }

        BondedVesselState targetState = targetState(request.transition());
        String candidateItemId = requireText(itemFor(targetState, config), "candidateItemId");
        long candidateGeneration = Math.addExact(binding.generation(), 1L);
        String candidateFingerprint = fingerprints.fingerprint(
                new BondedVesselItemFingerprintCodec.VesselItemMetadata(
                        candidateItemId, UUID.fromString(binding.bindingId()), binding.profileId(),
                        candidateGeneration, binding.configId(), targetState));
        long targetCooldown = startsCooldown(request.transition())
                ? cooldownDeadline(nowMs, config.transitionCooldownMs())
                : binding.cooldownUntilMs();
        BondedVesselProjectionStatus projection = request.transition() == BondedVesselTransition.RELEASE
                ? BondedVesselProjectionStatus.MISSING : BondedVesselProjectionStatus.PRESENT;
        return new Plan(targetState, projection, candidateItemId, candidateFingerprint,
                targetCooldown, policySnapshot(binding, request.transition(), config, targetState,
                        candidateItemId, candidateFingerprint, targetCooldown));
    }

    @Nonnull
    private static BondedVesselState targetState(@Nonnull BondedVesselTransition transition) {
        return switch (transition) {
            case SUMMON -> BondedVesselState.ACTIVE;
            case STORE, REPAIR_DEAD_TO_STORED -> BondedVesselState.STORED;
            case RELEASE -> BondedVesselState.RELEASED;
        };
    }

    private static boolean startsCooldown(BondedVesselTransition transition) {
        return transition == BondedVesselTransition.SUMMON || transition == BondedVesselTransition.STORE;
    }

    private static long cooldownDeadline(long nowMs, long cooldownMs) {
        if (cooldownMs == 0L) return 0L;
        try {
            return Math.addExact(nowMs, cooldownMs);
        } catch (ArithmeticException overflow) {
            return cooldownMs > 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    private static String itemFor(BondedVesselState state, SpawnerVesselConfigView config) {
        return switch (state) {
            case STORED, STORING -> config.storedItemId();
            case ACTIVE, SUMMONING -> config.activeItemId();
            case DEAD -> config.deadItemId();
            case LOST -> config.lostItemId();
            case RELEASING, RELEASED -> config.emptyItemId();
        };
    }

    private static String policySnapshot(BondedVesselBindingRecord binding,
                                         BondedVesselTransition transition,
                                         SpawnerVesselConfigView config,
                                         BondedVesselState targetState,
                                         String candidateItemId,
                                         String candidateFingerprint,
                                         long targetCooldown) {
        return "{"
                + "\"schema\":1,"
                + "\"configId\":" + quote(config.configId()) + ","
                + "\"configRevision\":" + config.configRevision() + ","
                + "\"mode\":\"BONDED\","
                + "\"transition\":" + quote(transition.name()) + ","
                + "\"priorState\":" + quote(binding.lifecycleState().name()) + ","
                + "\"targetState\":" + quote(targetState.name()) + ","
                + "\"priorGeneration\":" + binding.generation() + ","
                + "\"candidateGeneration\":" + (binding.generation() + 1L) + ","
                + "\"candidateItemId\":" + quote(candidateItemId) + ","
                + "\"candidateItemFingerprint\":" + quote(candidateFingerprint) + ","
                + "\"transitionCooldownMs\":" + config.transitionCooldownMs() + ","
                + "\"targetCooldownUntilMs\":" + targetCooldown + ","
                + "\"storeMaxDistance\":" + Double.toString(config.storeMaxDistance()) + ","
                + "\"storeParticleSystem\":" + nullableQuote(config.storeParticleSystem()) + ","
                + "\"storeSoundEvent\":" + nullableQuote(config.storeSoundEvent()) + ","
                + "\"requireOwner\":" + config.requireOwner() + ","
                + "\"allowStoreInCombat\":" + config.allowStoreInCombat()
                + "}";
    }

    private static String nullableQuote(String value) {
        return value == null ? "null" : quote(value);
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    @FunctionalInterface
    public interface ConfigResolver {
        @Nonnull
        Optional<SpawnerVesselConfigView> resolve(@Nonnull String configId, long configRevision);
    }
}
