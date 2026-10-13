package net.enthusia.loreitems.paper;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses physical location key versions WITHOUT migrating or trusting historic evidence.
 * A legacy world name is not a world UUID and cannot safely be resolved without
 * a separately approved, evidence-backed migration/reobservation.
 */
final class PaperPhysicalLocationKeys {
    private static final String COORD = "(-?\\d+)";
    private static final String UUID_TEXT = "([0-9a-fA-F-]{36})";
    private static final Pattern BLOCK = Pattern.compile(
            "^block-v2:" + UUID_TEXT + ":" + COORD + ":" + COORD + ":" + COORD + "$");
    private static final Pattern DOUBLE = Pattern.compile(
            "^double-chest:" + UUID_TEXT + ":" + COORD + ":" + COORD + ":"
                    + COORD + "\\|" + COORD + ":" + COORD + ":" + COORD
                    + ":left=([01])$");
    private static final Pattern LEGACY = Pattern.compile(
            "^([a-z0-9_.-]+:[a-z0-9_./-]+):" + COORD + ":" + COORD + ":" + COORD + "$");

    private PaperPhysicalLocationKeys() {}

    sealed interface Parsed permits LegacyBlock, UuidBlock, UuidPair {}

    record LegacyBlock(String worldNameKey, int x, int y, int z) implements Parsed {}
    record UuidBlock(UUID worldId, int x, int y, int z) implements Parsed {}
    record UuidPair(PaperInventoryReference.DoubleChestBlock pair) implements Parsed {}

    static Optional<Parsed> parse(String key) {
        if (key == null) {
            return Optional.empty();
        }
        try {
            Matcher block = BLOCK.matcher(key);
            if (block.matches()) {
                return Optional.of(new UuidBlock(
                        strictUuid(block.group(1)), number(block, 2),
                        number(block, 3), number(block, 4)));
            }
            Matcher pair = DOUBLE.matcher(key);
            if (pair.matches()) {
                return parsePair(key, pair).map(value -> (Parsed) new UuidPair(value));
            }
            Matcher legacy = LEGACY.matcher(key);
            if (legacy.matches() && !key.startsWith("block-v2:")) {
                return Optional.of(new LegacyBlock(legacy.group(1),
                        number(legacy, 2), number(legacy, 3), number(legacy, 4)));
            }
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<PaperInventoryReference.DoubleChestBlock> parsePair(
            String text, Matcher match) {
        UUID world = strictUuid(match.group(1));
        var first = new PaperDoubleChestIdentity.Position(
                number(match, 2), number(match, 3), number(match, 4));
        var second = new PaperDoubleChestIdentity.Position(
                number(match, 5), number(match, 6), number(match, 7));
        var left = match.group(8).equals("0") ? first : second;
        var ref = new PaperInventoryReference.DoubleChestBlock(world, first, second, left);
        return ref.stableKey().equals(text) ? Optional.of(ref) : Optional.empty();
    }

    private static int number(Matcher match, int group) {
        return Integer.parseInt(match.group(group));
    }

    private static UUID strictUuid(String input) {
        UUID parsed = UUID.fromString(input);
        if (!parsed.toString().equalsIgnoreCase(input)) {
            throw new IllegalArgumentException("Not a canonical UUID");
        }
        return parsed;
    }
}
