package com.alechilles.alecstamework.vessels.runtime;

import com.alechilles.alecstamework.api.BondedVesselMode;
import com.alechilles.alecstamework.api.BondedVesselSourceItemEvidence;
import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.SpawnerVesselConfigView;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwSpawnerVesselConfigResolver;
import com.google.gson.Gson;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Production bridge between ordinary spawner callbacks and bonded-vessel orchestration. */
public final class BondedVesselSpawnerBridge {
    private static final String INITIAL_CALLER = "tamework:bonded-capture";
    private final BondedVesselInitialBindingService initialBindings;
    private final BondedVesselInteractionDispatcher interactions;
    private final TwSpawnerVesselConfigResolver configs;
    private final BondedVesselItemFingerprintCodec fingerprints =
            new BondedVesselItemFingerprintCodec();
    private final Gson gson = new Gson();

    public BondedVesselSpawnerBridge(
            @Nonnull BondedVesselInitialBindingService initialBindings,
            @Nonnull BondedVesselInteractionDispatcher interactions,
            @Nonnull TwSpawnerVesselConfigResolver configs) {
        this.initialBindings = Objects.requireNonNull(initialBindings, "initialBindings");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /** Fast fail-closed validation used before capture mutates profile/population authority. */
    public boolean canBindSource(@Nonnull ItemStack source) {
        Objects.requireNonNull(source, "source");
        return source.getQuantity() == 1 && configs.resolveForItemId(source.getItemId())
                .filter(view -> view.mode() == BondedVesselMode.BONDED)
                .filter(view -> source.getItemId().equals(view.emptyItemId()))
                .isPresent();
    }

    /** Builds the immutable generation-one target only for a currently bonded source config. */
    @Nonnull
    public Optional<InitialCapturePlan> prepareInitialCapture(
            @Nonnull UUID actorUuid,
            int inventorySlot,
            @Nonnull ItemStack source,
            @Nonnull ItemStack capturedTemplate,
            @Nonnull String profileId,
            long committedProfileRevision,
            @Nullable UUID populationOperationId) {
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(capturedTemplate, "capturedTemplate");
        profileId = requireText(profileId, "profileId");
        if (inventorySlot < 0 || committedProfileRevision < 0L) return Optional.empty();
        SpawnerVesselConfigView config = configs.resolveForItemId(source.getItemId())
                .filter(view -> view.mode() == BondedVesselMode.BONDED)
                .orElse(null);
        if (config == null || !source.getItemId().equals(config.emptyItemId())
                || source.getQuantity() != 1) return Optional.empty();

        UUID bindingId = stableUuid("binding", profileId);
        UUID operationId = stableUuid("initial-bind", profileId + ":"
                + committedProfileRevision);
        BondedVesselItemFingerprintCodec.VesselItemMetadata metadata =
                new BondedVesselItemFingerprintCodec.VesselItemMetadata(
                        config.storedItemId(), bindingId, profileId, 1L,
                        config.configId(), BondedVesselState.STORED);
        String targetFingerprint = fingerprints.fingerprint(metadata);
        ItemStack target = withItemId(capturedTemplate, config.storedItemId())
                .withMetadata(TameworkMetadataKeys.VESSEL_BINDING_ID,
                        Codec.STRING, bindingId.toString().toLowerCase())
                .withMetadata(TameworkMetadataKeys.VESSEL_PROFILE_ID,
                        Codec.STRING, profileId)
                .withMetadata(TameworkMetadataKeys.VESSEL_GENERATION, Codec.LONG, 1L)
                .withMetadata(TameworkMetadataKeys.VESSEL_CONFIG_ID,
                        Codec.STRING, config.configId())
                .withMetadata(TameworkMetadataKeys.VESSEL_STATE,
                        Codec.STRING, BondedVesselState.STORED.name());
        String sourceFingerprint = sourceFingerprint(
                actorUuid, inventorySlot, source, profileId);
        BondedVesselSourceItemEvidence targetEvidence =
                new BondedVesselSourceItemEvidence(
                        target.getItemId(),
                        BondedVesselHeldSlotEvidenceFactory.holderEvidenceId(actorUuid),
                        BondedVesselHeldSlotEvidenceFactory.HOTBAR_CONTAINER_PATH,
                        inventorySlot, 1L, targetFingerprint);
        String sourceContext = gson.toJson(sourceContext(
                actorUuid, inventorySlot, source.getItemId(), sourceFingerprint));
        String policy = gson.toJson(Map.of(
                "schema", 1,
                "mode", "BONDED",
                "transition", "INITIAL_BIND",
                "configId", config.configId(),
                "configRevision", config.configRevision(),
                "targetState", BondedVesselState.STORED.name()));
        BondedVesselInitialBindingService.Request request =
                new BondedVesselInitialBindingService.Request(
                        operationId, bindingId, INITIAL_CALLER,
                        "initial-bind:" + profileId, populationOperationId == null
                        ? null : populationOperationId.toString(), profileId, actorUuid,
                        committedProfileRevision, config.configId(), config.configRevision(),
                        source.getItemId(), target.getItemId(), sourceFingerprint,
                        targetFingerprint, sourceContext, gson.toJson(targetEvidence), policy,
                        populationOperationId == null ? null : populationOperationId.toString());
        return Optional.of(new InitialCapturePlan(request, source, target));
    }

    /** Starts the durable binding and finalizes the exact capture source at most once. */
    @Nonnull
    public CompletionStage<BondedVesselInitialBindingService.Result> bind(
            @Nonnull InitialCapturePlan plan,
            @Nonnull ExactSourceReplacer replacer) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(replacer, "replacer");
        return initialBindings.bind(plan.request(), request -> {
            CompletionStage<Boolean> replacement;
            try {
                replacement = replacer.replace(plan.source(), plan.target());
            } catch (RuntimeException | LinkageError failure) {
                replacement = null;
            }
            if (replacement == null) {
                return CompletableFuture.completedFuture(sourceResult(
                        BondedVesselInitialBindingService.SourceStatus.INDETERMINATE,
                        "initial-binding-source-dispatch-unavailable"));
            }
            return replacement.handle((replaced, failure) -> failure != null
                    ? sourceResult(BondedVesselInitialBindingService.SourceStatus.INDETERMINATE,
                    "initial-binding-source-dispatch-failed")
                    : Boolean.TRUE.equals(replaced)
                    ? sourceResult(BondedVesselInitialBindingService.SourceStatus.REPLACED,
                    "initial-binding-source-replaced")
                    : sourceResult(BondedVesselInitialBindingService.SourceStatus.SOURCE_CHANGED,
                    "initial-binding-source-changed"));
        });
    }

    @Nonnull
    public CompletionStage<BondedVesselInteractionDispatcher.Result> toggle(
            @Nonnull UUID actorUuid,
            int inventorySlot,
            @Nonnull String expectedItemId,
            @Nullable PopulationAdmissionLocation destination) {
        return interactions.toggle(new BondedVesselInteractionDispatcher.Request(
                actorUuid, inventorySlot, expectedItemId, destination));
    }

    @Nonnull
    private static BondedVesselInitialBindingService.SourceFinalization sourceResult(
            BondedVesselInitialBindingService.SourceStatus status, String reason) {
        return new BondedVesselInitialBindingService.SourceFinalization(status, reason);
    }

    private static Map<String, Object> sourceContext(
            UUID actorUuid, int inventorySlot, String itemId, String fingerprint) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("version", 1);
        context.put("sourceItemId", itemId);
        context.put("sourceHolderEvidenceId",
                BondedVesselHeldSlotEvidenceFactory.holderEvidenceId(actorUuid));
        context.put("sourceContainerPath",
                BondedVesselHeldSlotEvidenceFactory.HOTBAR_CONTAINER_PATH);
        context.put("sourceInventorySlot", inventorySlot);
        context.put("sourceInventoryRevision", 0L);
        context.put("sourceItemFingerprint", fingerprint);
        return context;
    }

    private static ItemStack withItemId(ItemStack source, String itemId) {
        return itemId.equals(source.getItemId()) ? source : new ItemStack(
                itemId, 1, source.getDurability(), source.getMaxDurability(), source.getMetadata());
    }

    private static UUID stableUuid(String kind, String value) {
        return UUID.nameUUIDFromBytes(("tamework:bonded-vessel:" + kind + ":" + value)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String sourceFingerprint(
            UUID actorUuid, int slot, ItemStack source, String profileId) {
        return stableUuid("initial-source", actorUuid + ":" + slot + ":"
                + source.getItemId() + ":" + source.getQuantity() + ":" + profileId).toString();
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    public record InitialCapturePlan(
            @Nonnull BondedVesselInitialBindingService.Request request,
            @Nonnull ItemStack source,
            @Nonnull ItemStack target) {
        public InitialCapturePlan {
            request = Objects.requireNonNull(request, "request");
            source = Objects.requireNonNull(source, "source");
            target = Objects.requireNonNull(target, "target");
        }
    }

    @FunctionalInterface
    public interface ExactSourceReplacer {
        @Nonnull CompletionStage<Boolean> replace(
                @Nonnull ItemStack expected, @Nonnull ItemStack replacement);
    }
}
