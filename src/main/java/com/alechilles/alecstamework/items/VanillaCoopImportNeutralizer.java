package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.VanillaCoopImportEvidenceCodec.SourcePlan;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceEvidence;
import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Removes one exact, durably authorized vanilla resident from the Hytale 0.5.6 list.
 *
 * <p>This is intentionally separate from the read-only audit adapter. It resolves and validates
 * the supported layout independently and never removes by a stale slot: the current list is
 * rescanned using the immutable stable payload immediately before mutation.</p>
 */
public final class VanillaCoopImportNeutralizer {
    public enum Status {
        REMOVED,
        ALREADY_ABSENT,
        AMBIGUOUS,
        CHANGED_OR_UNKNOWN,
        UNSUPPORTED,
        FAILED
    }

    public record Result(@Nonnull Status status,
                         int matchesBefore,
                         int residentsAfter,
                         @Nullable String detail) {
        public Result {
            Objects.requireNonNull(status, "status");
            if (matchesBefore < 0 || residentsAfter < 0) {
                throw new IllegalArgumentException("neutralization counts must not be negative");
            }
        }

        public boolean absentAfter() {
            return status == Status.REMOVED || status == Status.ALREADY_ABSENT;
        }
    }

    private final VanillaCoopImportAdapter adapter;
    private final VanillaCoopImportEvidenceCodec evidenceCodec;
    private final Layout layout;

    public VanillaCoopImportNeutralizer() {
        this(
                new VanillaCoopImportAdapter(),
                new VanillaCoopImportEvidenceCodec(),
                CoopBlock.class,
                CoopBlock.CoopResident.class
        );
    }

    VanillaCoopImportNeutralizer(@Nonnull VanillaCoopImportAdapter adapter,
                                 @Nonnull VanillaCoopImportEvidenceCodec evidenceCodec,
                                 @Nonnull Class<?> coopClass,
                                 @Nonnull Class<?> residentClass) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.evidenceCodec = Objects.requireNonNull(evidenceCodec, "evidenceCodec");
        this.layout = Layout.resolve(coopClass, residentClass);
    }

    /** Mutates only when the current supported list contains exactly one exact source. */
    @Nonnull
    public Result neutralize(@Nonnull CoopBlock coop,
                             @Nonnull SourceEvidence source) {
        return neutralizeSource(coop, source);
    }

    @Nonnull
    Result neutralizeFixtureForTest(@Nonnull Object coop,
                                    @Nonnull SourceEvidence source) {
        return neutralizeSource(coop, source);
    }

    private Result neutralizeSource(Object coop, SourceEvidence source) {
        Objects.requireNonNull(coop, "coop");
        Objects.requireNonNull(source, "source");
        if (!layout.supported() || coop.getClass() != layout.coopClass()) {
            return result(Status.UNSUPPORTED, 0, 0, layout.detail());
        }
        final SourcePlan plan;
        try {
            plan = evidenceCodec.decodeSourcePlan(source);
        } catch (RuntimeException exception) {
            return result(Status.FAILED, 0, 0, "source_plan_invalid:" + detail(exception));
        }
        if (plan.disposition()
                == VanillaCoopImportEvidenceCodec.PlannedDisposition.QUARANTINED
                || plan.multiplicity() != 1) {
            return result(Status.FAILED, 0, 0, "quarantined_or_grouped_source_not_removable");
        }
        try {
            VanillaCoopImportAdapter.AuditResult audit = audit(coop);
            if (!audit.readable()) {
                return result(Status.UNSUPPORTED, 0, 0, audit.detail());
            }
            Match match = match(audit, plan.stablePayload(), source.persistentUuid());
            if (match.changedIdentity()) {
                return result(Status.CHANGED_OR_UNKNOWN, match.count(), audit.residents().size(),
                        "persistent_source_changed_since_audit");
            }
            if (match.count() == 0) {
                return result(Status.ALREADY_ABSENT, 0, audit.residents().size(), null);
            }
            if (match.count() != 1 || match.index() < 0) {
                return result(Status.AMBIGUOUS, match.count(), audit.residents().size(),
                        "exact_source_not_unique");
            }
            List<?> residents = residents(coop);
            if (residents.size() != audit.residents().size()) {
                return result(Status.CHANGED_OR_UNKNOWN, match.count(), residents.size(),
                        "resident_list_changed_after_audit");
            }
            residents.remove(match.index());
            VanillaCoopImportAdapter.AuditResult after = audit(coop);
            if (!after.readable()) {
                return result(Status.FAILED, 1, residents.size(), "post_remove_audit_failed");
            }
            Match remaining = match(after, plan.stablePayload(), source.persistentUuid());
            if (remaining.count() != 0 || remaining.changedIdentity()
                    || after.residents().size() != audit.residents().size() - 1) {
                return result(Status.FAILED, 1, after.residents().size(),
                        "post_remove_exact_absence_not_observed");
            }
            return result(Status.REMOVED, 1, after.residents().size(), null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return result(Status.FAILED, 0, 0, "neutralization_failed:" + detail(exception));
        }
    }

    private Match match(VanillaCoopImportAdapter.AuditResult audit,
                        String stablePayload,
                        @Nullable UUID persistentUuid) {
        int count = 0;
        int index = -1;
        boolean changedIdentity = false;
        for (int current = 0; current < audit.residents().size(); current++) {
            VanillaCoopImportAdapter.ResidentEvidence resident = audit.residents().get(current);
            VanillaCoopImportEvidenceCodec.StableSource copied =
                    evidenceCodec.copyStableSource(resident);
            if (stablePayload.equals(copied.payload())) {
                count++;
                index = current;
            } else if (persistentUuid != null && persistentUuid.equals(copied.persistentUuid())) {
                changedIdentity = true;
            }
        }
        return new Match(count, index, changedIdentity);
    }

    private VanillaCoopImportAdapter.AuditResult audit(Object coop) {
        return coop instanceof CoopBlock vanilla
                ? adapter.auditForImport(vanilla)
                : adapter.auditFixtureForTest(coop);
    }

    @SuppressWarnings("unchecked")
    private List<Object> residents(Object coop) throws IllegalAccessException {
        Object value = layout.residents().get(coop);
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("vanilla resident field is no longer a list");
        }
        return (List<Object>) list;
    }

    private Result result(Status status, int matches, int after, @Nullable String detail) {
        return new Result(status, matches, Math.max(0, after), detail);
    }

    private static String detail(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private record Match(int count, int index, boolean changedIdentity) {
    }

    private record Layout(boolean supported,
                          Class<?> coopClass,
                          Field residents,
                          @Nullable String detail) {
        private static Layout resolve(Class<?> coopClass, Class<?> residentClass) {
            try {
                Field residents = coopClass.getDeclaredField("residents");
                int modifiers = residents.getModifiers();
                if (residents.getType() != List.class || !Modifier.isProtected(modifiers)
                        || Modifier.isStatic(modifiers)) {
                    return unsupported("unsupported_vanilla_resident_field_shape");
                }
                Type generic = residents.getGenericType();
                if (!(generic instanceof ParameterizedType parameterized)
                        || parameterized.getRawType() != List.class
                        || parameterized.getActualTypeArguments().length != 1
                        || parameterized.getActualTypeArguments()[0] != residentClass
                        || !residents.trySetAccessible()) {
                    return unsupported("unsupported_vanilla_resident_generic_or_access");
                }
                return new Layout(true, coopClass, residents, null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                return unsupported("unsupported_vanilla_neutralization_layout:"
                        + VanillaCoopImportNeutralizer.detail(exception));
            }
        }

        private static Layout unsupported(String detail) {
            return new Layout(false, null, null, detail);
        }
    }
}
