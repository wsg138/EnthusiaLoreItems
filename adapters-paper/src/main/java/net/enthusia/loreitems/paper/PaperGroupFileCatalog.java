package net.enthusia.loreitems.paper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private static final Set<String> SUPPORTED_KEYS = Set.of("display-name", "players");
    private static final int MAX_GROUP_FILE_BYTES = 1_048_576;
    private static final int MAX_RECIPIENTS = 100_000;

    private final Path groupsDirectory;
    private final Path completedDirectory;
    private final Path cancelledDirectory;

    public PaperGroupFileCatalog(Path dataDirectory) {
        Path data = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath()
                .normalize();
        groupsDirectory = data.resolve("groups");
        completedDirectory = groupsDirectory.resolve("completed");
        cancelledDirectory = groupsDirectory.resolve("cancelled");
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
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(groupsDirectory)) {
            for (Path candidate : stream) {
                String name = candidate.getFileName().toString();
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
        String stem = safeName.substring(0, safeName.length() - YAML_SUFFIX.length());
        Path target = groupsDirectory.resolve(stem + ACTIVE_MARKER + campaignId + YAML_SUFFIX);
        return moveNoReplace(source, target);
    }

    public Path repairActiveMarker(
            String originalSourceName,
            String expectedFingerprint,
            UUID campaignId) throws IOException {
        String safeName = validateSourceName(originalSourceName);
        String stem = safeName.substring(0, safeName.length() - YAML_SUFFIX.length());
        Path target = groupsDirectory.resolve(stem + ACTIVE_MARKER + campaignId + YAML_SUFFIX);
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return target;
        }
        Path source = groupsDirectory.resolve(safeName);
        if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(source)) {
            byte[] bytes = Files.readAllBytes(source);
            if (fingerprint(safeName, bytes).equals(expectedFingerprint)) {
                return moveNoReplace(source, target);
            }
        }
        return target;
    }

    public Path moveToCompleted(String originalSourceName, UUID campaignId) throws IOException {
        return moveTerminal(originalSourceName, campaignId, completedDirectory, "completed");
    }

    public Path moveToCancelled(String originalSourceName, UUID campaignId) throws IOException {
        return moveTerminal(originalSourceName, campaignId, cancelledDirectory, "cancelled");
    }

    Path groupsDirectory() {
        return groupsDirectory;
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
                if (!real.getParent().equals(safeRoot)) {
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
        long size = Files.size(source);
        if (size <= 0L || size > MAX_GROUP_FILE_BYTES) {
            throw new IllegalArgumentException(
                    "file size must be between 1 and " + MAX_GROUP_FILE_BYTES + " bytes");
        }
        byte[] bytes = Files.readAllBytes(source);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(new String(bytes, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException exception) {
            throw new IllegalArgumentException("malformed YAML: " + safeMessage(exception), exception);
        }
        Set<String> keys = yaml.getKeys(false);
        Set<String> unsupported = new HashSet<>(keys);
        unsupported.removeAll(SUPPORTED_KEYS);
        if (!unsupported.isEmpty()) {
            throw new IllegalArgumentException("unsupported top-level keys: " + unsupported);
        }
        Object displayValue = yaml.get("display-name");
        if (!(displayValue instanceof String displayName) || displayName.isBlank()) {
            throw new IllegalArgumentException("display-name must be a non-blank string");
        }
        Object playerValue = yaml.get("players");
        if (!(playerValue instanceof List<?> playerList) || playerList.isEmpty()) {
            throw new IllegalArgumentException("players must be a non-empty YAML list");
        }
        if (playerList.size() > MAX_RECIPIENTS) {
            throw new IllegalArgumentException("players exceeds the bounded recipient limit");
        }
        List<GroupFileRecipient> recipients = parseRecipients(playerList);
        return new GroupFileDefinition(
                sourceName,
                displayName,
                fingerprint(sourceName, bytes),
                recipients);
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
            GroupFileRecipient recipient;
            try {
                recipient = GroupFileRecipient.parse(player);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "players[" + index + "]: " + safeMessage(exception), exception);
            }
            if (!normalizedKeys.add(recipient.recipientKey().value())) {
                throw new IllegalArgumentException(
                        "players[" + index + "] duplicates normalized recipient "
                                + recipient.originalValue());
            }
            recipients.add(recipient);
        }
        return List.copyOf(recipients);
    }

    private Path secureRegularSource(String sourceName) throws IOException {
        Path safeRoot = groupsDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path source = groupsDirectory.resolve(sourceName).normalize();
        if (Files.isSymbolicLink(source)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || !source.toRealPath(LinkOption.NOFOLLOW_LINKS).getParent().equals(safeRoot)) {
            throw new IOException("Group source is no longer a safe regular file");
        }
        return source;
    }

    private Path moveTerminal(
            String originalSourceName,
            UUID campaignId,
            Path terminalDirectory,
            String suffix) throws IOException {
        String safeName = validateSourceName(originalSourceName);
        String stem = safeName.substring(0, safeName.length() - YAML_SUFFIX.length());
        Path active = groupsDirectory.resolve(stem + ACTIVE_MARKER + campaignId + YAML_SUFFIX);
        Path target = terminalDirectory.resolve(
                stem + "." + suffix + "-" + campaignId + YAML_SUFFIX);
        Files.createDirectories(terminalDirectory);
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return target;
        }
        if (!Files.isRegularFile(active, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(active)) {
            return target;
        }
        return moveNoReplace(active, target);
    }

    private static Path moveNoReplace(Path source, Path target) throws IOException {
        try {
            return Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            return Files.move(source, target);
        }
    }

    private static boolean isDiscoverableName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(YAML_SUFFIX) && !lower.contains(ACTIVE_MARKER);
    }

    private static String validateSourceName(String sourceName) {
        Objects.requireNonNull(sourceName, "sourceName");
        String normalized = sourceName.strip();
        if (normalized.isEmpty()
                || !normalized.toLowerCase(Locale.ROOT).endsWith(YAML_SUFFIX)
                || normalized.contains("/")
                || normalized.contains("\\")
                || normalized.equals(".")
                || normalized.equals("..")
                || !Path.of(normalized).getFileName().toString().equals(normalized)
                || normalized.toLowerCase(Locale.ROOT).contains(ACTIVE_MARKER)) {
            throw new IllegalArgumentException("Invalid group source name");
        }
        return normalized;
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
