package com.alechilles.alecstamework.api.commandui;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Detached description of one Tamework-owned command-menu action.
 *
 * <p>The description is presentation data. It is not an authority object and
 * it cannot execute an action. Execution requires a handle issued for the
 * current session.</p>
 */
public final class CommandUiAction {
    /** Common semantic action kinds used by the built-in command menu. */
    public enum Kind {
        SELECT_COMMAND,
        ASSIGN_HOTSWAP,
        ASSIGN_GROUP,
        MANAGE_GROUPS,
        LINK,
        UNLINK,
        TOGGLE_ACTIVE,
        TOGGLE_BREEDING,
        RELEASE,
        CULL,
        RESPAWN,
        LOCATE,
        RECALL,
        SET_HOME,
        RETURN_HOME,
        SUMMON,
        DISMISS,
        REVIVE,
        ABANDON,
        TOGGLE_FLIGHT,
        TOGGLE_SHOULDER_RIDE,
        PANEL_PREFERENCE,
        OTHER
    }

    /** Reusable target-free descriptors for common menu actions. */
    public static final CommandUiAction SELECT_COMMAND =
            new CommandUiAction(Kind.SELECT_COMMAND, null, false);
    public static final CommandUiAction MANAGE_GROUPS =
            new CommandUiAction(Kind.MANAGE_GROUPS, null, false);
    public static final CommandUiAction SUMMON =
            new CommandUiAction(Kind.SUMMON, null, false);
    public static final CommandUiAction DISMISS =
            new CommandUiAction(Kind.DISMISS, null, false);
    public static final CommandUiAction REVIVE =
            new CommandUiAction(Kind.REVIVE, null, true);

    private final String kind;
    @Nullable
    private final UUID targetId;
    @Nullable
    private final String value;
    private final boolean confirmationRequired;

    /** Creates a target-free action description. */
    public CommandUiAction(@Nonnull String kind) {
        this(kind, null, null, false);
    }

    /** Creates an action description bound to a displayed target identity. */
    public CommandUiAction(@Nonnull String kind, @Nullable UUID targetId) {
        this(kind, targetId, null, false);
    }

    /** Creates an action description with a concrete value and confirmation flag. */
    public CommandUiAction(
            @Nonnull String kind,
            @Nullable UUID targetId,
            @Nullable String value,
            boolean confirmationRequired
    ) {
        this.kind = requireKind(kind);
        this.targetId = targetId;
        this.value = normalize(value);
        this.confirmationRequired = confirmationRequired;
    }

    /** Convenience constructor for callers using the stable kind enum. */
    public CommandUiAction(
            @Nonnull Kind kind,
            @Nullable UUID targetId,
            boolean confirmationRequired
    ) {
        this(Objects.requireNonNull(kind, "kind").name(), targetId, null,
                confirmationRequired);
    }

    /** Convenience constructor for a value-bearing enum action. */
    public CommandUiAction(
            @Nonnull Kind kind,
            @Nullable UUID targetId,
            @Nullable String value,
            boolean confirmationRequired
    ) {
        this(Objects.requireNonNull(kind, "kind").name(), targetId, value,
                confirmationRequired);
    }

    @Nonnull
    public static CommandUiAction of(@Nonnull String kind) {
        return new CommandUiAction(kind);
    }

    @Nonnull
    public static CommandUiAction forTarget(
            @Nonnull String kind,
            @Nonnull UUID targetId
    ) {
        return new CommandUiAction(kind, targetId);
    }

    @Nonnull
    public static CommandUiAction requiringConfirmation(
            @Nonnull String kind,
            @Nullable UUID targetId
    ) {
        return new CommandUiAction(kind, targetId, null, true);
    }

    /** Returns the stable semantic kind string used by a renderer. */
    @Nonnull
    public String kind() {
        return kind;
    }

    /** Alias for code that calls the semantic kind a type. */
    @Nonnull
    public String semanticKind() {
        return kind;
    }

    /** Returns the optional displayed target identity. */
    @Nullable
    public UUID targetId() {
        return targetId;
    }

    /** Alias for target-oriented integrations. */
    @Nullable
    public UUID targetUuid() {
        return targetId;
    }

    /** Returns the optional concrete value selected by the user. */
    @Nullable
    public String value() {
        return value;
    }

    public boolean confirmationRequired() {
        return confirmationRequired;
    }

    public boolean requiresConfirmation() {
        return confirmationRequired;
    }

    /** Returns the enum value when the kind is one of the built-in kinds. */
    @Nonnull
    public Kind builtInKind() {
        try {
            return Kind.valueOf(kind);
        } catch (IllegalArgumentException ignored) {
            return Kind.OTHER;
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CommandUiAction that)) return false;
        return confirmationRequired == that.confirmationRequired
                && kind.equals(that.kind)
                && Objects.equals(targetId, that.targetId)
                && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, targetId, value, confirmationRequired);
    }

    @Override
    public String toString() {
        return "CommandUiAction[kind=" + kind
                + ", targetId=" + targetId
                + ", value=" + value
                + ", confirmationRequired=" + confirmationRequired + "]";
    }

    @Nonnull
    private static String requireKind(@Nullable String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Command UI action kind is required.");
        }
        return normalized;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
