package com.alechilles.alecstamework.persistence.migration;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

/** Shared strict scalar decoding for the released legacy DAT line formats. */
class LegacyDatValueDecoder {
    String[] fields(
            LegacyDatBundleSnapshot.SourceLine line,
            int minimum,
            int maximum
    ) throws PublicImportException {
        String[] parts = line.value().split("\\t", -1);
        if (parts.length < minimum || parts.length > maximum) {
            throw malformed(line, "fieldCount");
        }
        return parts;
    }

    @Nullable
    String base64At(
            String[] parts,
            int index,
            LegacyDatBundleSnapshot.SourceLine line,
            String field
    ) throws PublicImportException {
        return index < parts.length ? base64(parts[index], line, field) : null;
    }

    @Nullable
    String base64(
            String raw,
            LegacyDatBundleSnapshot.SourceLine line,
            String field
    ) throws PublicImportException {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(raw);
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return decoded.isBlank() ? null : decoded;
        } catch (IllegalArgumentException | CharacterCodingException failure) {
            throw malformed(line, field);
        }
    }

    String requiredUuid(
            String raw,
            LegacyDatBundleSnapshot.SourceLine line,
            String field
    ) throws PublicImportException {
        String value = raw == null ? null : raw.trim();
        if (value == null || value.isBlank()) {
            throw malformed(line, field);
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) {
                throw malformed(line, field);
            }
            return parsed.toString();
        } catch (IllegalArgumentException failure) {
            throw malformed(line, field);
        }
    }

    @Nullable
    String optionalUuid(
            String raw,
            LegacyDatBundleSnapshot.SourceLine line,
            String field
    ) throws PublicImportException {
        return raw == null || raw.isBlank() ? null : requiredUuid(raw, line, field);
    }

    @Nullable
    String optionalUuidAt(
            String[] parts,
            int index,
            LegacyDatBundleSnapshot.SourceLine line,
            String field
    ) throws PublicImportException {
        return index < parts.length ? optionalUuid(parts[index], line, field) : null;
    }

    long requiredLong(
            String raw,
            LegacyDatBundleSnapshot.SourceLine line,
            String field
    ) throws PublicImportException {
        if (raw == null || raw.isBlank()) {
            throw malformed(line, field);
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException failure) {
            throw malformed(line, field);
        }
    }

    long longAt(
            String[] parts,
            int index,
            long fallback,
            LegacyDatBundleSnapshot.SourceLine line,
            String field
    ) throws PublicImportException {
        return index < parts.length ? requiredLong(parts[index], line, field) : fallback;
    }

    int requiredInt(
            String raw,
            LegacyDatBundleSnapshot.SourceLine line,
            String field
    ) throws PublicImportException {
        if (raw == null || raw.isBlank()) {
            throw malformed(line, field);
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException failure) {
            throw malformed(line, field);
        }
    }

    double doubleAt(
            String[] parts,
            int index,
            double fallback,
            LegacyDatBundleSnapshot.SourceLine line,
            String field
    ) throws PublicImportException {
        return index >= parts.length
                ? fallback
                : requiredDouble(parts[index], line, field);
    }

    double requiredDouble(
            String raw,
            LegacyDatBundleSnapshot.SourceLine line,
            String field
    ) throws PublicImportException {
        if (raw == null || raw.isBlank()) {
            throw malformed(line, field);
        }
        try {
            double value = Double.parseDouble(raw.trim());
            if (!Double.isFinite(value)) {
                throw malformed(line, field);
            }
            return value;
        } catch (NumberFormatException failure) {
            throw malformed(line, field);
        }
    }

    @Nullable
    Double nullableDoubleAt(
            String[] parts,
            int index,
            LegacyDatBundleSnapshot.SourceLine line,
            String field
    ) throws PublicImportException {
        if (index >= parts.length || parts[index].isBlank()) {
            return null;
        }
        return requiredDouble(parts[index], line, field);
    }

    boolean requiredBoolean(
            String raw,
            LegacyDatBundleSnapshot.SourceLine line,
            String field
    ) throws PublicImportException {
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        throw malformed(line, field);
    }

    boolean booleanAt(
            String[] parts,
            int index,
            boolean fallback,
            LegacyDatBundleSnapshot.SourceLine line,
            String field
    ) throws PublicImportException {
        return index < parts.length ? requiredBoolean(parts[index], line, field) : fallback;
    }

    @Nullable
    String enumAt(
            String[] parts,
            int index,
            LegacyDatBundleSnapshot.SourceLine line,
            String field
    ) throws PublicImportException {
        if (index >= parts.length || parts[index].isBlank()) {
            return null;
        }
        String value = parts[index].trim();
        Set<String> supported = Set.of(
                "STARVATION", "DEHYDRATION", "STARVATION_AND_DEHYDRATION",
                "PLAYER", "NPC", "ENVIRONMENT", "UNKNOWN"
        );
        if (!supported.contains(value)) {
            throw malformed(line, field);
        }
        return value;
    }

    @Nullable
    Vector vector(
            String raw,
            LegacyDatBundleSnapshot.SourceLine line,
            String field
    ) throws PublicImportException {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] values = raw.split(",", -1);
        if (values.length != 3) {
            throw malformed(line, field);
        }
        return new Vector(
                requiredDouble(values[0], line, field),
                requiredDouble(values[1], line, field),
                requiredDouble(values[2], line, field)
        );
    }

    PublicImportException malformed(
            LegacyDatBundleSnapshot.SourceLine line,
            String field
    ) {
        return new PublicImportException(
                "MALFORMED_LEGACY_DAT_ROW",
                "Malformed legacy DAT row: " + line.evidence(field)
        );
    }

    record Vector(double x, double y, double z) {
    }
}
