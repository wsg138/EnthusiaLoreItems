package net.enthusia.loreitems.paper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

public final class PaperGroupFileCatalog {
    private static final String YAML_SUFFIX = ".yml";
    private static final String ACTIVE_MARKER = ".active-";
    private static final String RECOVERY_TEMP_PREFIX = ".distribution-marker-recovery-";
    private static final String RECOVERY_TEMP_SUFFIX = ".tmp";
    private static final Set<String> SUPPORTED_KEYS = Set.of("display-name", "players");
    private static final int MAX_GROUP_FILE_BYTES = 1_048_576;
    private static final int MAX_RECIPIENTS = 100_000;
    private static final int MAX_GROUP_DIRECTORY_ENTRIES = 10_000;

    private final Path groupsDirectory;
    private final Path completedDirectory;
    private final Path cancelledDirectory;
    private final int maxDirectoryEntries;

    public PaperGroupFileCatalog(Path dataDirectory) {
        this(dataDirectory, MAX_GROUP_DIRECTORY_ENTRIES);
    }

    PaperGroupFileCatalog(Path dataDirectory, int maxDirectoryEntries) {
        Path data = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath()
                .normalize();
        if (maxDirectoryEntries < 1 || maxDirectoryEntries > MAX_GROUP_DIRECTORY_ENTRIES) {
            throw new IllegalArgumentException("maxDirectoryEntries is outside supported bounds");
        }
        groupsDirectory = data.resolve("groups");
        completedDirectory = groupsDirectory.resolve("completed");
        cancelledDirectory = groupsDirectory.resolve("cancelled");
        this.maxDirectoryEntries = maxDirectoryEntries;
    }

    public void initializeDirectories() throws IOException {
        Files.createDirectories(groupsDirectory);
        Files.createDirectories(completedDirectory);
        Files.createDirectories(cancelledDirectory);
    }

    public GroupFileCatalogSnapshot reload() throws IOException {
        initializeDirectories();
        Path safeRoot = groupsDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS);
        List<GroupFileDefinition> valid = new ArrayList<>();
        List<GroupFileValidationFailure> invalid = new ArrayList<>();
        int inspectedEntries = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(groupsDirectory)) {
            for (Path candidate : stream) {
                Path fileName = candidate.getFileName();
                if (fileName == null) {
                    continue;
                }
                String name = fileName.toString();
                if (isRecoveryTempName(name)) {
                    continue;
                }
                inspectedEntries++;
                if (inspectedEntries > maxDirectoryEntries) {
                    throw new IOException(
                            "groups directory exceeds the bounded entry limit of "
                                    + maxDirectoryEntries);
                }
                if (!isDiscoverableName(name)) {
                    continue;
                }
                inspectCandidate(safeRoot, candidate, name, valid, invalid);
            }
        }
        valid.sort(Comparator.comparing(GroupFileDefinition::sourceName));
        invalid.sort(Comparator.comparing(GroupFileValidationFailure::sourceName));
        return new GroupFileCatalogSnapshot(valid, invalid);
    }

    public GroupFileDefinition inspect(String sourceName) throws IOException {
        initializeDirectories();
        String safeName = validateSourceName(sourceName);
        Path safeRoot = groupsDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path source = groupsDirectory.resolve(safeName).normalize();
        List<GroupFileDefinition> valid = new ArrayList<>();
        List<GroupFileValidationFailure> invalid = new ArrayList<>();
        inspectCandidate(safeRoot, source, safeName, valid, invalid);
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", invalid.getFirst().diagnostics()));
        }
        if (valid.isEmpty()) {
            throw new IllegalArgumentException("Group file is not a discoverable .yml source");
        }
        return valid.getFirst();
    }

    public Path moveToActive(GroupFileDefinition definition, UUID campaignId) throws IOException {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(campaignId, "campaignId");
        String safeName = validateSourceName(definition.sourceName());
        Path source = secureRegularSource(safeName);
        String actualFingerprint = fingerprint(safeName, Files.readAllBytes(source));
        if (!actualFingerprint.equals(definition.sourceFingerprint())) {
            throw new IOException("Group source changed after validation; durable campaign remains authoritative");
        }
        Path target = activeMarkerPath(safeName, campaignId);
        return moveNoReplace(source, target);
    }

    public Path repairActiveMarker(
            String originalSourceName,
            String expectedFingerprint,
            UUID campaignId) throws IOException {
        String safeName = validateSourceName(originalSourceName);
        Objects.requireNonNull(expectedFingerprint, "expectedFingerprint");
        Objects.requireNonNull(campaignId, "campaignId");
        initializeDirectories();
        Path target = activeMarkerPath(safeName, campaignId);
        if (safeRegularFile(target)) {
            return target;
        }
        rejectUnsafeExistingMarker(target);
        Path source = groupsDirectory.resolve(safeName);
        if (safeRegularFile(source)) {
            byte[] bytes = Files.readAllBytes(source);
            if (fingerprint(safeName, bytes).equals(expectedFingerprint)) {
                return moveNoReplace(source, target);
            }
        }
        return synthesizeRecoveryMarker(target, safeName, expectedFingerprint, campaignId);
    }

    public Path moveToCompleted(String originalSourceName, UUID campaignId) throws IOException {
        return moveTerminal(originalSourceName, campaignId, completedDirectory, "completed");
    }

    public Path moveToCancelled(String originalSourceName, UUID campaignId) throws IOException {
        return moveTerminal(originalSourceName, campaignId, cancelledDirectory, "cancelled");
    }

    private void inspectCandidate(
            Path safeRoot,
            Path candidate,
            String name,
            List<GroupFileDefinition> valid,
            List<GroupFileValidationFailure> invalid) {
        List<String> diagnostics = new ArrayList<>();
        try {
            if (Files.isSymbolicLink(candidate)) {
                diagnostics.add("symbolic links are not allowed");
            } else if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                diagnostics.add("source is not a regular readable file");
            } else if (!Files.isReadable(candidate)) {
                diagnostics.add("source is not readable");
            } else {
                Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
                Path parent = real.getParent();
                if (parent == null || !parent.equals(safeRoot)) {
                    diagnostics.add("source escapes the groups directory");
                } else {
                    valid.add(parse(name, real));
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            diagnostics.add(safeMessage(exception));
        }
        if (!diagnostics.isEmpty()) {
            invalid.add(new GroupFileValidationFailure(name, diagnostics));
        }
    }

    private static GroupFileDefinition parse(String sourceName, Path source) throws IOException {
        validateFileSize(source);
        byte[] bytes = Files.readAllBytes(source);
        YamlConfiguration yaml = loadYaml(bytes);
        validateSupportedKeys(yaml);
        String displayName = requireDisplayName(yaml);
        List<?> playerList = requirePlayers(yaml);
        List<GroupFileRecipient> recipients = parseRecipients(playerList);
        return new GroupFileDefinition(
                sourceName,
                displayName,
                fingerprint(sourceName, bytes),
                recipients);
    }

    private static void validateFileSize(Path source) throws IOException {
        long size = Files.size(source);
        if (size <= 0L || size > MAX_GROUP_FILE_BYTES) {
            throw new IllegalArgumentException(
                    "file size must be between 1 and " + MAX_GROUP_FILE_BYTES + " bytes");
        }
    }

    private static YamlConfiguration loadYaml(byte[] bytes) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(new String(bytes, StandardCharsets.UTF_8));
            return yaml;
        } catch (InvalidConfigurationException exception) {
            throw new IllegalArgumentException("malformed YAML: " + safeMessage(exception), exception);
        }
    }

    private static void validateSupportedKeys(YamlConfiguration yaml) {
        Set<String> unsupported = new HashSet<>(yaml.getKeys(false));
        unsupported.removeAll(SUPPORTED_KEYS);
        if (!unsupported.isEmpty()) {
            throw new IllegalArgumentException("unsupported top-level keys: " + unsupported);
        }
    }

    private static String requireDisplayName(YamlConfiguration yaml) {
        Object value = yaml.get("display-name");
        if (!(value instanceof String displayName) || displayName.isBlank()) {
            throw new IllegalArgumentException("display-name must be a non-blank string");
        }
        return displayName;
    }

    private static List<?> requirePlayers(YamlConfiguration yaml) {
        Object value = yaml.get("players");
        if (!(value instanceof List<?> players) || players.isEmpty()) {
            throw new IllegalArgumentException("players must be a non-empty YAML list");
        }
        if (players.size() > MAX_RECIPIENTS) {
            throw new IllegalArgumentException("players exceeds the bounded recipient limit");
        }
        return players;
    }

    private static List<GroupFileRecipient> parseRecipients(List<?> playerList) {
        List<GroupFileRecipient> recipients = new ArrayList<>(playerList.size());
        Set<String> normalizedKeys = new HashSet<>();
        for (int index = 0; index < playerList.size(); index++) {
            Object value = playerList.get(index);
            if (!(value instanceof String player)) {
                throw new IllegalArgumentException(
                        "players[" + index + "] must be a string recipient");
            }
            GroupFileRecipient recipient = parseRecipient(index, player);
            if (!normalizedKeys.add(recipient.recipientKey().value())) {
                throw new IllegalArgumentException(
                        "players[" + index + "] duplicates normalized recipient "
                                + recipient.originalValue());
            }
            recipients.add(recipient);
        }
        return List.copyOf(recipients);
    }

    private static GroupFileRecipient parseRecipient(int index, String player) {
        try {
            return GroupFileRecipient.parse(player);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "players[" + index + "]: " + safeMessage(exception), exception);
        }
    }

    private Path secureRegularSource(String sourceName) throws IOException {
        Path safeRoot = groupsDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path source = groupsDirectory.resolve(sourceName).normalize();
        if (Files.isSymbolicLink(source)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Group source is no longer a safe regular file");
        }
        Path realParent = source.toRealPath(LinkOption.NOFOLLOW_LINKS).getParent();
        if (realParent == null || !realParent.equals(safeRoot)) {
            throw new IOException("Group source is no longer a safe regular file");
        }
        return source;
    }

    private Path synthesizeRecoveryMarker(
            Path target,
            String sourceName,
            String sourceFingerprint,
            UUID campaignId) throws IOException {
        Path temporary = Files.createTempFile(
                groupsDirectory, RECOVERY_TEMP_PREFIX, RECOVERY_TEMP_SUFFIX);
        try {
            Files.writeString(
                    temporary,
                    recoveryMarkerContent(sourceName, sourceFingerprint, campaignId),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                return moveNoReplace(temporary, target);
            } catch (FileAlreadyExistsException exception) {
                if (safeRegularFile(target)) {
                    return target;
                }
                throw exception;
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String recoveryMarkerContent(
            String sourceName, String sourceFingerprint, UUID campaignId) {
        return "# Recovered by EnthusiaLoreItems from durable campaign state.\n"
                + "# This operator marker is not a reusable distribution source.\n"
                + "campaign-id: " + campaignId + '\n'
                + "source-name: " + sourceName + '\n'
                + "source-fingerprint: " + sourceFingerprint + '\n';
    }

    private Path moveTerminal(
            String originalSourceName,
            UUID campaignId,
            Path terminalDirectory,
            String suffix) throws IOException {
        String safeName = validateSourceName(originalSourceName);
        Path active = activeMarkerPath(safeName, campaignId);
        String stem = safeName.substring(0, safeName.length() - YAML_SUFFIX.length());
        Path target = terminalDirectory.resolve(
                stem + "." + suffix + "-" + campaignId + YAML_SUFFIX);
        Files.createDirectories(terminalDirectory);
        if (safeRegularFile(target)) {
            return target;
        }
        rejectUnsafeExistingMarker(target);
        if (!safeRegularFile(active)) {
            rejectUnsafeExistingMarker(active);
            return target;
        }
        return moveNoReplace(active, target);
    }

    private Path activeMarkerPath(String safeName, UUID campaignId) {
        String stem = safeName.substring(0, safeName.length() - YAML_SUFFIX.length());
        return groupsDirectory.resolve(stem + ACTIVE_MARKER + campaignId + YAML_SUFFIX);
    }

    private static boolean safeRegularFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(path);
    }

    private static void rejectUnsafeExistingMarker(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !safeRegularFile(path)) {
            throw new IOException("Campaign marker path exists but is not a safe regular file");
        }
    }

    /**
     * Preserve the one-use marker contract even when the target already exists. Omitting both
     * REPLACE_EXISTING and ATOMIC_MOVE gives Files.move its portable no-replace behavior.
     */
    private static Path moveNoReplace(Path source, Path target) throws IOException {
        return Files.move(source, target);
    }

    private static boolean isDiscoverableName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(YAML_SUFFIX) && !lower.contains(ACTIVE_MARKER);
    }

    private static boolean isRecoveryTempName(String name) {
        return name.startsWith(RECOVERY_TEMP_PREFIX) && name.endsWith(RECOVERY_TEMP_SUFFIX);
    }

    private static String validateSourceName(String sourceName) {
        Objects.requireNonNull(sourceName, "sourceName");
        String normalized = sourceName.strip();
        requireNonBlankSourceName(normalized);
        requireYamlSuffix(normalized);
        requireNoPathSeparators(normalized);
        requireNoDotSegment(normalized);
        requireSingleFileName(normalized);
        requireNoActiveMarker(normalized);
        return normalized;
    }

    private static void requireNonBlankSourceName(String sourceName) {
        if (sourceName.isEmpty()) {
            throw invalidSourceName();
        }
    }

    private static void requireYamlSuffix(String sourceName) {
        if (!sourceName.toLowerCase(Locale.ROOT).endsWith(YAML_SUFFIX)) {
            throw invalidSourceName();
        }
    }

    private static void requireNoPathSeparators(String sourceName) {
        if (sourceName.contains("/") || sourceName.contains("\\")) {
            throw invalidSourceName();
        }
    }

    private static void requireNoDotSegment(String sourceName) {
        if (sourceName.equals(".") || sourceName.equals("..")) {
            throw invalidSourceName();
        }
    }

    private static void requireSingleFileName(String sourceName) {
        Path fileName = Path.of(sourceName).getFileName();
        if (fileName == null || !fileName.toString().equals(sourceName)) {
            throw invalidSourceName();
        }
    }

    private static void requireNoActiveMarker(String sourceName) {
        if (sourceName.toLowerCase(Locale.ROOT).contains(ACTIVE_MARKER)) {
            throw invalidSourceName();
        }
    }

    private static IllegalArgumentException invalidSourceName() {
        return new IllegalArgumentException("Invalid group source name");
    }

    private static String fingerprint(String sourceName, byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(sourceName.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(bytes);
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", exception);
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
