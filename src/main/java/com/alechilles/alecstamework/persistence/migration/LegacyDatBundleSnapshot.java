package com.alechilles.alecstamework.persistence.migration;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, byte-exact snapshot of the released legacy DAT file set. */
final class LegacyDatBundleSnapshot {
    static final String CAPTURES_FILE = "CommandLinkedNpcCaptures.dat";
    static final String COOPS_FILE = "CommandLinkedNpcCoops.dat";
    static final String DEATHS_FILE = "CommandLinkedNpcDeaths.dat";
    static final String LOST_FILE = "CommandLinkedNpcLost.dat";
    static final String RETIRED_COOP_SNAPSHOTS_FILE = "CoopResidentSnapshots.dat";
    static final List<String> FILE_NAMES = List.of(
            CAPTURES_FILE,
            COOPS_FILE,
            DEATHS_FILE,
            LOST_FILE,
            RETIRED_COOP_SNAPSHOTS_FILE
    );

    private final Path sourceDirectory;
    private final Map<String, byte[]> files;
    private final LegacySourceFingerprint fingerprint;

    private LegacyDatBundleSnapshot(
            Path sourceDirectory,
            Map<String, byte[]> files,
            LegacySourceFingerprint fingerprint
    ) {
        this.sourceDirectory = sourceDirectory;
        LinkedHashMap<String, byte[]> copied = new LinkedHashMap<>();
        files.forEach((name, bytes) -> copied.put(name, Arrays.copyOf(bytes, bytes.length)));
        this.files = Collections.unmodifiableMap(copied);
        this.fingerprint = fingerprint;
    }

    static LegacyDatBundleSnapshot capture(Path sourceDirectory) throws Exception {
        Path directory = sourceDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new PublicImportException(
                    "LEGACY_DAT_DIRECTORY_MISSING",
                    "Legacy DAT source must be a real directory: " + directory
            );
        }
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        long totalBytes = 0L;
        long newestModifiedAtMs = 0L;
        for (String fileName : FILE_NAMES) {
            Path source = directory.resolve(fileName);
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new PublicImportException(
                        "LEGACY_DAT_SOURCE_NOT_REGULAR",
                        "Legacy DAT source is not a regular file: " + fileName
                );
            }
            byte[] bytes = Files.readAllBytes(source);
            files.put(fileName, bytes);
            totalBytes = Math.addExact(totalBytes, bytes.length);
            FileTime modified = Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS);
            newestModifiedAtMs = Math.max(newestModifiedAtMs, modified.toMillis());
        }
        verifyUnchanged(directory, files);
        return new LegacyDatBundleSnapshot(
                directory,
                files,
                new LegacySourceFingerprint(
                        fingerprint(files),
                        totalBytes,
                        newestModifiedAtMs
                )
        );
    }

    boolean hasSourceFiles() {
        return !files.isEmpty();
    }

    LegacySourceFingerprint fingerprint() {
        return fingerprint;
    }

    String sourceName() {
        return "legacy-dat-bundle[" + String.join(",", FILE_NAMES.stream()
                .filter(files::containsKey)
                .toList()) + "]";
    }

    boolean ownsPath(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        for (String fileName : FILE_NAMES) {
            if (sourceDirectory.resolve(fileName).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    List<SourceLine> lines(String fileName) throws PublicImportException {
        byte[] bytes = files.get(fileName);
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new PublicImportException(
                    "MALFORMED_LEGACY_DAT_UTF8",
                    "Invalid UTF-8 in " + fileName
            );
        }
        String[] rawLines = decoded.split("\\R", -1);
        ArrayList<SourceLine> result = new ArrayList<>(rawLines.length);
        for (int index = 0; index < rawLines.length; index++) {
            if (!rawLines[index].isBlank()) {
                result.add(new SourceLine(fileName, index + 1, rawLines[index]));
            }
        }
        return List.copyOf(result);
    }

    private static String fingerprint(Map<String, byte[]> files) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String fileName : FILE_NAMES) {
            byte[] name = fileName.getBytes(StandardCharsets.UTF_8);
            byte[] bytes = files.get(fileName);
            digest.update(intBytes(name.length));
            digest.update(name);
            digest.update((byte) (bytes == null ? 0 : 1));
            if (bytes != null) {
                digest.update(intBytes(bytes.length));
                digest.update(bytes);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void verifyUnchanged(Path directory, Map<String, byte[]> expected)
            throws Exception {
        for (String fileName : FILE_NAMES) {
            Path source = directory.resolve(fileName);
            boolean exists = Files.exists(source, LinkOption.NOFOLLOW_LINKS);
            byte[] bytes = expected.get(fileName);
            if (exists != (bytes != null)
                    || (exists && (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                        || !Arrays.equals(bytes, Files.readAllBytes(source))))) {
                throw new PublicImportException(
                        "LEGACY_DAT_SOURCE_CHANGED_DURING_READ",
                        "Legacy DAT source changed during snapshot: " + fileName
                );
            }
        }
    }

    private static byte[] intBytes(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    record SourceLine(String fileName, int lineNumber, String value) {
        String evidence(String field) {
            return fileName + ":" + lineNumber + ":" + field;
        }
    }
}
