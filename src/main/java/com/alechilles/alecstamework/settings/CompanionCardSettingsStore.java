package com.alechilles.alecstamework.settings;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.companioncard.CompanionCardFBehaviour;
import com.alechilles.alecstamework.companioncard.CompanionCardRenameMode;
import com.alechilles.alecstamework.config.assets.TwNamesConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.hypixel.hytale.logger.HytaleLogger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The server's companion card settings, stored next to the `/tw settings` file: what F on a companion does by
 * default, whether players may choose for themselves, how the card's rename pencil works, and which name lists
 * the Random button draws from when renaming is free. Admins set them
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
        try {
            return parse(NetworkSettingsLayer.effective(NetworkSettingsLayer.SECTION_COMPANION_CARD,
                    Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null), file, logger);
        } catch (IOException | JsonParseException ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex)
                        .log("Failed to read the Tamework companion card settings from %s; using the built-in defaults.", file);
            }
            return Settings.DEFAULT;
        }
    }

    /**
     * The settings a document holds; a null or blank document gives the defaults.
     *
     * @throws JsonParseException when {@code raw} is not a settings document
     */
    @Nonnull
    public static Settings parse(@Nullable String raw) {
        return parse(raw, null, null);
    }

    @Nonnull
    private static Settings parse(@Nullable String raw, @Nullable Path file, @Nullable HytaleLogger logger) {
        Document document = raw == null || raw.isBlank() ? null : GSON.fromJson(raw, Document.class);
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
                CompanionCardRenameMode.fromConfigValue(document.renameMode),
                document.freeRenameNameLists == null
                        ? Settings.DEFAULT.freeRenameNameLists()
                        : document.freeRenameNameLists,
                document.freeRenameRegions == null
                        ? Settings.DEFAULT.freeRenameRegions()
                        : regionsFrom(document.freeRenameRegions, file, logger));
    }

    /** The regions a list of config values names; unknown values are skipped. */
    @Nonnull
    public static Set<TwNamesConfig.Region> regionsFrom(@Nonnull Collection<String> values) {
        return regionsFrom(values, null, null);
    }

    @Nonnull
    private static Set<TwNamesConfig.Region> regionsFrom(@Nonnull Collection<String> values,
                                                         @Nullable Path file, @Nullable HytaleLogger logger) {
        EnumSet<TwNamesConfig.Region> regions = EnumSet.noneOf(TwNamesConfig.Region.class);
        for (String value : values) {
            TwNamesConfig.Region region = TwNamesConfig.Region.fromConfigValueOrNull(value);
            if (region != null) {
                regions.add(region);
            } else if (logger != null) {
                logger.at(Level.WARNING).log(
                        "Tamework companion card settings in %s have an unknown freeRenameRegions value '%s'; skipping it.",
                        file, value);
            }
        }
        return regions;
    }

    /** The full document for these settings. */
    @Nonnull
    public static String toJson(@Nonnull Settings settings) {
        Document document = new Document();
        document.defaultFBehaviour = settings.defaultFBehaviour().toConfigValue();
        document.allowPlayerChoice = settings.allowPlayerChoice();
        document.renameMode = settings.renameMode().toConfigValue();
        document.freeRenameNameLists = settings.freeRenameNameLists();
        document.freeRenameRegions = settings.freeRenameRegions().stream()
                .map(TwNamesConfig.Region::toConfigValue)
                .toList();
        return GSON.toJson(document);
    }

    /** Writes the settings and, once written, makes them current. Blocks on file I/O. */
    public static synchronized boolean save(@Nonnull Path file, @Nonnull Settings settings,
                                            @Nullable HytaleLogger logger) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, NetworkSettingsLayer.localForm(NetworkSettingsLayer.SECTION_COMPANION_CARD,
                    toJson(settings)), StandardCharsets.UTF_8);
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
     * @param freeRenameNameLists the {@code TwNames} asset ids a free rename's Random button draws from, sorted and
     *                            without blanks or duplicates; empty hides the button
     * @param freeRenameRegions   the regional name sets a free rename draws from; empty hides the button
     */
    public record Settings(@Nonnull CompanionCardFBehaviour defaultFBehaviour,
                           boolean allowPlayerChoice,
                           @Nonnull CompanionCardRenameMode renameMode,
                           @Nonnull List<String> freeRenameNameLists,
                           @Nonnull Set<TwNamesConfig.Region> freeRenameRegions) {
        public static final String DEFAULT_NAME_LIST = "TwNamesDefault";
        public static final Settings DEFAULT = new Settings(CompanionCardFBehaviour.CARD, true,
                CompanionCardRenameMode.ITEM, List.of(DEFAULT_NAME_LIST), EnumSet.allOf(TwNamesConfig.Region.class));

        public Settings {
            Objects.requireNonNull(defaultFBehaviour, "defaultFBehaviour");
            Objects.requireNonNull(renameMode, "renameMode");
            TreeSet<String> lists = new TreeSet<>();
            for (String id : Objects.requireNonNull(freeRenameNameLists, "freeRenameNameLists")) {
                if (id != null && !id.isBlank()) {
                    lists.add(id.trim());
                }
            }
            freeRenameNameLists = List.copyOf(lists);
            EnumSet<TwNamesConfig.Region> regions = EnumSet.noneOf(TwNamesConfig.Region.class);
            regions.addAll(Objects.requireNonNull(freeRenameRegions, "freeRenameRegions"));
            freeRenameRegions = Collections.unmodifiableSet(regions);
        }
    }

    /**
     * defaultFBehaviour is CARD or CYCLE; renameMode is FREE, ITEM or OFF; freeRenameRegions holds NorthAmerica,
     * German, Spanish or BrazilianPortuguese. A missing list keeps its default; an empty one hides the Random button.
     */
    private static final class Document {
        private String defaultFBehaviour;
        private Boolean allowPlayerChoice;
        private String renameMode;
        private List<String> freeRenameNameLists;
        private List<String> freeRenameRegions;
    }
}
