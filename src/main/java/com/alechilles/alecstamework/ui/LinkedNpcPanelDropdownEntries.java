package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Gives one linked-panel page a stable dropdown list when a supplier rebuilds
 * equivalent {@link DropdownEntryInfo} instances on each read.
 */
final class LinkedNpcPanelDropdownEntries implements Supplier<List<DropdownEntryInfo>> {
    private final Supplier<List<DropdownEntryInfo>> source;
    private List<org.bson.BsonValue> previousSignature = List.of();
    private List<DropdownEntryInfo> previousEntries = List.of();

    LinkedNpcPanelDropdownEntries(@Nonnull Supplier<List<DropdownEntryInfo>> source) {
        this.source = source;
    }

    @Override
    public List<DropdownEntryInfo> get() {
        List<DropdownEntryInfo> entries = source.get();
        List<DropdownEntryInfo> resolved = entries == null ? List.of() : entries;
        List<org.bson.BsonValue> signature = signature(resolved);
        if (signature.equals(previousSignature)) {
            return previousEntries;
        }
        previousSignature = signature;
        previousEntries = List.copyOf(resolved);
        return previousEntries;
    }

    private static List<org.bson.BsonValue> signature(List<DropdownEntryInfo> entries) {
        ArrayList<org.bson.BsonValue> values = new ArrayList<>(entries.size());
        for (DropdownEntryInfo entry : entries) {
            values.add(entry == null ? org.bson.BsonNull.VALUE : DropdownEntryInfo.CODEC.encode(entry));
        }
        return List.copyOf(values);
    }
}
