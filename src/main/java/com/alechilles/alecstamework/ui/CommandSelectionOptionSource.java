package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandEntry;
import com.alechilles.alecstamework.localization.LocalizedText;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds and resolves the bounded command-wheel option snapshot. */
final class CommandSelectionOptionSource {
    private CommandSelectionOptionSource() {
    }

    @Nonnull
    static Option[] build(
            @Nullable TwCommandItemConfig config,
            @Nullable Predicate<CommandEntry> predicate,
            @Nullable String language,
            int maximum
    ) {
        if (config == null || config.getCommandList() == null
                || config.getCommandList().length == 0
                || maximum <= 0) {
            return new Option[0];
        }
        List<Option> result = new ArrayList<>(
                Math.min(config.getCommandList().length, maximum)
        );
        for (CommandEntry entry : config.getCommandList()) {
            if (entry == null || entry.getId() == null
                    || entry.getId().isBlank()
                    || predicate != null && !predicate.test(entry)) {
                continue;
            }
            result.add(new Option(
                    entry.getId(),
                    LocalizedText.resolveConfigValue(
                            language,
                            entry.getDisplayName(),
                            entry.getId()
                    )
            ));
            if (result.size() >= maximum) {
                break;
            }
        }
        return result.toArray(new Option[0]);
    }

    static boolean contains(Option[] options, String commandId) {
        if (commandId == null || commandId.isBlank()) {
            return false;
        }
        for (Option option : options) {
            if (option != null && CommandUiIdParser.commandIdEquals(
                    option.id(), commandId
            )) {
                return true;
            }
        }
        return false;
    }

    static String currentLabel(
            Option[] options,
            String selectedCommandId,
            String language
    ) {
        if (selectedCommandId == null || selectedCommandId.isBlank()) {
            return LocalizedText.resolve(
                    language, "tamework.ui.commandMenu.current.none"
            );
        }
        for (Option option : options) {
            if (option != null && CommandUiIdParser.commandIdEquals(
                    option.id(), selectedCommandId
            )) {
                return LocalizedText.format(
                        language,
                        "tamework.ui.commandMenu.current.value",
                        option.label()
                );
            }
        }
        return LocalizedText.format(
                language,
                "tamework.ui.commandMenu.current.value",
                selectedCommandId
        );
    }

    record Option(@Nonnull String id, @Nonnull String label) {
    }
}
