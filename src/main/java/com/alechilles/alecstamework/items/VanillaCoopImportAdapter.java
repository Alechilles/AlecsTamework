package com.alechilles.alecstamework.items;

import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.server.core.entity.reference.PersistentRef;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.npc.metadata.CapturedNPCMetadata;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Read-only import audit adapter for the Hytale 0.5.6 vanilla {@link CoopBlock} layout.
 *
 * <p>This adapter is not a managed-coop occupancy source and must not be called by normal managed
 * runtime admission, capture, release, or spawning paths. It validates the entire supported
 * reflective layout before reading any source value, then returns structurally immutable evidence
 * without mutating the vanilla coop, residents, metadata, references, or produce storage.</p>
 */
public final class VanillaCoopImportAdapter {
    public static final String SUPPORTED_LAYOUT_ID = "hytale-0.5.6-coopblock-v1";

    public enum AuditStatus {
        EMPTY,
        SUPPORTED,
        UNSUPPORTED,
        FAILED
    }

    /** Coop-level source evidence relevant to identity, resident order, and produce storage. */
    public record CoopEvidence(@Nullable String coopAssetId,
                               int sourceResidentCount,
                               @Nonnull String residentListClassName,
                               @Nullable ItemContainer rawProduceStorage) {
        public CoopEvidence {
            Objects.requireNonNull(residentListClassName, "residentListClassName");
            if (sourceResidentCount < 0) {
                throw new IllegalArgumentException("sourceResidentCount must not be negative");
            }
        }
    }

    /** Exact point-in-time evidence read for one vanilla resident list entry. */
    public record ResidentEvidence(int residentSlot,
                                   int sourceOrder,
                                   @Nullable CapturedNPCMetadata rawMetadata,
                                   boolean deployedToWorld,
                                   @Nullable PersistentRef rawPersistentRef,
                                   @Nullable UUID persistentUuid,
                                   @Nullable Instant lastProduced) {
        public ResidentEvidence {
            if (residentSlot < 0 || sourceOrder < 0) {
                throw new IllegalArgumentException("resident slot and source order must not be negative");
            }
        }
    }

    /** Immutable structural result; raw Hytale objects are retained only as import evidence. */
    public record AuditResult(@Nonnull AuditStatus status,
                              @Nonnull String layoutId,
                              @Nullable CoopEvidence coop,
                              @Nonnull List<ResidentEvidence> residents,
                              @Nullable String detail) {
        public AuditResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(layoutId, "layoutId");
            residents = residents == null ? List.of() : List.copyOf(residents);
        }

        public boolean readable() {
            return status == AuditStatus.EMPTY || status == AuditStatus.SUPPORTED;
        }
    }

    private final Layout layout;

    public VanillaCoopImportAdapter() {
        this(CoopBlock.class, CoopBlock.CoopResident.class);
    }

    VanillaCoopImportAdapter(Class<?> coopClass, Class<?> residentClass) {
        this.layout = Layout.resolve(coopClass, residentClass);
    }

    /**
     * Audits one vanilla coop exclusively for explicit migration/import reconciliation.
     */
    @Nonnull
    public AuditResult auditForImport(@Nullable CoopBlock vanillaCoop) {
        return auditSource(vanillaCoop);
    }

    @Nonnull
    AuditResult auditFixtureForTest(@Nullable Object fixture) {
        return auditSource(fixture);
    }

    @Nonnull
    private AuditResult auditSource(@Nullable Object source) {
        if (!layout.supported()) {
            return result(AuditStatus.UNSUPPORTED, null, List.of(), layout.detail());
        }
        if (source == null) {
            return result(AuditStatus.FAILED, null, List.of(), "vanilla_coop_source_required");
        }
        if (source.getClass() != layout.coopClass()) {
            return result(
                    AuditStatus.UNSUPPORTED,
                    null,
                    List.of(),
                    "unsupported_vanilla_coop_runtime_class:" + source.getClass().getName()
            );
        }
        try {
            Object residentsValue = layout.residents().get(source);
            if (!(residentsValue instanceof List<?> rawResidents)) {
                return result(
                        AuditStatus.FAILED,
                        null,
                        List.of(),
                        "vanilla_coop_residents_value_is_not_a_list"
                );
            }
            ArrayList<?> stableResidents = new ArrayList<>(rawResidents);
            CoopEvidence coopEvidence = readCoopEvidence(source, rawResidents, stableResidents.size());
            if (stableResidents.isEmpty()) {
                return result(AuditStatus.EMPTY, coopEvidence, List.of(), null);
            }

            ArrayList<ResidentEvidence> evidence = new ArrayList<>(stableResidents.size());
            for (int order = 0; order < stableResidents.size(); order++) {
                Object resident = stableResidents.get(order);
                if (resident == null || resident.getClass() != layout.residentClass()) {
                    return result(
                            AuditStatus.UNSUPPORTED,
                            coopEvidence,
                            List.of(),
                            "unsupported_vanilla_coop_resident_element:" + order
                    );
                }
                ResidentEvidence entry = readResidentEvidence(resident, order);
                if (entry == null) {
                    return result(
                            AuditStatus.UNSUPPORTED,
                            coopEvidence,
                            List.of(),
                            "unsupported_vanilla_coop_resident_evidence_shape:" + order
                    );
                }
                evidence.add(entry);
            }
            return result(AuditStatus.SUPPORTED, coopEvidence, evidence, null);
        } catch (IllegalAccessException exception) {
            return result(
                    AuditStatus.FAILED,
                    null,
                    List.of(),
                    "vanilla_coop_evidence_access_failed:" + exception.getClass().getSimpleName()
            );
        } catch (RuntimeException | LinkageError exception) {
            return result(
                    AuditStatus.FAILED,
                    null,
                    List.of(),
                    "vanilla_coop_evidence_read_failed:" + exceptionDetail(exception)
            );
        }
    }

    @Nonnull
    private CoopEvidence readCoopEvidence(Object source,
                                          List<?> rawResidents,
                                          int residentCount) throws IllegalAccessException {
        Object coopAssetId = layout.coopAssetId().get(source);
        Object storage = layout.itemContainer().get(source);
        if (coopAssetId != null && !(coopAssetId instanceof String)) {
            throw new IllegalStateException("coop asset id runtime type changed");
        }
        if (storage != null && !(storage instanceof ItemContainer)) {
            throw new IllegalStateException("coop item container runtime type changed");
        }
        return new CoopEvidence(
                (String) coopAssetId,
                residentCount,
                rawResidents.getClass().getName(),
                (ItemContainer) storage
        );
    }

    @Nullable
    private ResidentEvidence readResidentEvidence(Object resident,
                                                  int order) throws IllegalAccessException {
        Object metadata = layout.metadata().get(resident);
        Object persistentRef = layout.persistentRef().get(resident);
        Object lastProduced = layout.lastProduced().get(resident);
        if (metadata != null && metadata.getClass() != CapturedNPCMetadata.class) {
            return null;
        }
        if (persistentRef != null && persistentRef.getClass() != PersistentRef.class) {
            return null;
        }
        if (lastProduced != null && lastProduced.getClass() != Instant.class) {
            return null;
        }
        UUID uuid = persistentRef == null
                ? null
                : (UUID) layout.persistentRefUuid().get(persistentRef);
        return new ResidentEvidence(
                order,
                order,
                (CapturedNPCMetadata) metadata,
                layout.deployedToWorld().getBoolean(resident),
                (PersistentRef) persistentRef,
                uuid,
                (Instant) lastProduced
        );
    }

    @Nonnull
    private AuditResult result(AuditStatus status,
                               @Nullable CoopEvidence coop,
                               List<ResidentEvidence> residents,
                               @Nullable String detail) {
        return new AuditResult(status, SUPPORTED_LAYOUT_ID, coop, residents, detail);
    }

    @Nonnull
    private static String exceptionDetail(Throwable exception) {
        String detail = exception.getMessage();
        return detail == null || detail.isBlank()
                ? exception.getClass().getSimpleName()
                : detail;
    }

    /** Exact reflective layout admitted by this one import-only adapter. */
    private record Layout(boolean supported,
                          Class<?> coopClass,
                          Class<?> residentClass,
                          Field coopAssetId,
                          Field residents,
                          Field itemContainer,
                          Field metadata,
                          Field persistentRef,
                          Field deployedToWorld,
                          Field lastProduced,
                          Field persistentRefUuid,
                          String detail) {
        @Nonnull
        private static Layout resolve(Class<?> coopClass, Class<?> residentClass) {
            if (coopClass == null || residentClass == null) {
                return unsupported("vanilla_coop_layout_class_required");
            }
            try {
                Field coopAssetId = protectedField(coopClass, "coopAssetId", String.class);
                Field residents = protectedField(coopClass, "residents", List.class);
                validateResidentGeneric(residents, residentClass);
                Field itemContainer = protectedField(coopClass, "itemContainer", ItemContainer.class);
                Field metadata = protectedField(
                        residentClass, "metadata", CapturedNPCMetadata.class);
                Field persistentRef = protectedField(
                        residentClass, "persistentRef", PersistentRef.class);
                Field deployedToWorld = protectedField(
                        residentClass, "deployedToWorld", boolean.class);
                Field lastProduced = protectedField(
                        residentClass, "lastProduced", Instant.class);
                Field persistentRefUuid = protectedField(
                        PersistentRef.class, "uuid", UUID.class);
                makeAccessible(
                        coopAssetId, residents, itemContainer, metadata, persistentRef,
                        deployedToWorld, lastProduced, persistentRefUuid
                );
                return new Layout(
                        true,
                        coopClass,
                        residentClass,
                        coopAssetId,
                        residents,
                        itemContainer,
                        metadata,
                        persistentRef,
                        deployedToWorld,
                        lastProduced,
                        persistentRefUuid,
                        null
                );
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                return unsupported("unsupported_vanilla_coop_layout:" + exceptionDetail(exception));
            }
        }

        @Nonnull
        private static Layout unsupported(String detail) {
            return new Layout(
                    false, null, null, null, null, null, null, null, null, null, null, detail
            );
        }

        @Nonnull
        private static Field protectedField(Class<?> owner,
                                            String name,
                                            Class<?> exactType) throws NoSuchFieldException {
            Field field = owner.getDeclaredField(name);
            int modifiers = field.getModifiers();
            if (field.getType() != exactType || !Modifier.isProtected(modifiers)
                    || Modifier.isStatic(modifiers)) {
                throw new NoSuchFieldException(
                        owner.getName() + "#" + name + ":unexpected_field_shape"
                );
            }
            return field;
        }

        private static void validateResidentGeneric(Field residents,
                                                    Class<?> residentClass)
                throws NoSuchFieldException {
            Type genericType = residents.getGenericType();
            if (!(genericType instanceof ParameterizedType parameterized)
                    || parameterized.getRawType() != List.class) {
                throw new NoSuchFieldException("residents:missing_exact_list_generic");
            }
            Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length != 1 || arguments[0] != residentClass) {
                throw new NoSuchFieldException("residents:unexpected_generic_element");
            }
        }

        private static void makeAccessible(Field... fields) {
            for (Field field : fields) {
                if (!field.trySetAccessible()) {
                    throw new IllegalStateException(
                            "vanilla_coop_field_inaccessible:" + field.getName()
                    );
                }
            }
        }
    }
}
