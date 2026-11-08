package com.alechilles.alecstamework.settings;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.companioncard.CompanionCardFBehaviour;
import com.alechilles.alecstamework.companioncard.CompanionCardRenameMode;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.hypixel.hytale.logger.HytaleLogger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The server's companion card settings, stored next to the `/tw settings` file: what F on a companion does by
 * default, whether players may choose for themselves, and how the card's rename pencil works. Admins set them
 * from `/tw settings`, or edit the file and run the config reload.
 *
 * <p>F reads {@link #current()} inside an NPC role tick, so it never touches the file. The file is read only by
 * {@link #load} at plugin start and on config reload, both off the world thread.</p>
 */
public final class CompanionCardSettingsStore {
    public static final String FILE_NAME = "tamework-companion-card.json";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private static volatile Settings cached = Settings.DEFAULT;

    private CompanionCardSettingsStore() {
    }

    @Nonnull
    public static Path resolveFile(@Nonnull Tamework plugin) {
        return TameworkSettingsStore.resolveSettingsDirectory(plugin).resolve(FILE_NAME).normalize();
    }

    /** The settings in effect: the built-in defaults until {@link #load} or {@link #save} ran. Never reads the file. */
    @Nonnull
    public static Settings current() {
        return cached;
    }

    /**
     * Reads the file into the cache; a missing file means the built-in defaults and is not created until an admin
     * saves. Blocks on file I/O, so never call it on a world thread.
     */
    @Nonnull
    public static Settings load(@Nonnull Path file, @Nullable HytaleLogger logger) {
        Settings loaded = read(file, logger);
        cached = loaded;
        return loaded;
    }

    /** Parses the file without touching the cache. A missing or unknown value falls back to its default. */
    @Nonnull
    static Settings read(@Nonnull Path file, @Nullable HytaleLogger logger) {
        if (!Files.isRegularFile(file)) {
            return Settings.DEFAULT;
        }
        try {
            Document document = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Document.class);
            if (document == null) {
                return Settings.DEFAULT;
            }
            CompanionCardFBehaviour behaviour = CompanionCardFBehaviour.fromConfigValueOrNull(document.defaultFBehaviour);
            if (behaviour == null && document.defaultFBehaviour != null && logger != null) {
                logger.at(Level.WARNING).log(
                        "Tamework companion card settings in %s have an unknown defaultFBehaviour '%s'; using %s.",
                        file, document.defaultFBehaviour, Settings.DEFAULT.defaultFBehaviour().toConfigValue());
            }
            return new Settings(
                    behaviour == null ? Settings.DEFAULT.defaultFBehaviour() : behaviour,
                    document.allowPlayerChoice == null ? Settings.DEFAULT.allowPlayerChoice() : document.allowPlayerChoice,
                    CompanionCardRenameMode.fromConfigValue(document.renameMode));
        } catch (IOException | JsonParseException ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex)
                        .log("Failed to read the Tamework companion card settings from %s; using the built-in defaults.", file);
            }
            return Settings.DEFAULT;
        }
    }

    /** Writes the settings and, once written, makes them current. Blocks on file I/O. */
    public static synchronized boolean save(@Nonnull Path file, @Nonnull Settings settings,
                                            @Nullable HytaleLogger logger) {
        Document document = new Document();
        document.defaultFBehaviour = settings.defaultFBehaviour().toConfigValue();
        document.allowPlayerChoice = settings.allowPlayerChoice();
        document.renameMode = settings.renameMode().toConfigValue();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(document), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex)
                        .log("Failed to write the Tamework companion card settings to %s.", file);
            }
            return false;
        }
        cached = settings;
        return true;
    }

    /**
     * Server companion card settings.
     *
     * @param defaultFBehaviour what F does for players who have not chosen, or for everyone when choice is locked
     * @param allowPlayerChoice whether a player's own choice overrides the default
     * @param renameMode        how the card's rename pencil works
     */
    public record Settings(@Nonnull CompanionCardFBehaviour defaultFBehaviour,
                           boolean allowPlayerChoice,
                           @Nonnull CompanionCardRenameMode renameMode) {
        public static final Settings DEFAULT = new Settings(CompanionCardFBehaviour.CARD, true, CompanionCardRenameMode.ITEM);

        public Settings {
            Objects.requireNonNull(defaultFBehaviour, "defaultFBehaviour");
            Objects.requireNonNull(renameMode, "renameMode");
        }
    }

    /** defaultFBehaviour is CARD or CYCLE; renameMode is FREE, ITEM or OFF. */
    private static final class Document {
        private String defaultFBehaviour;
        private Boolean allowPlayerChoice;
        private String renameMode;
    }
}
