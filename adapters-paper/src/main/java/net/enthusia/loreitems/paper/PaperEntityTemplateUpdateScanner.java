package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Reads lore-item identities from supported already-loaded entities. */
class PaperEntityTemplateUpdateScanner {
    private static final List<EquipmentSlot> ARMOR_STAND_SLOTS = List.of(
            EquipmentSlot.HAND,
            EquipmentSlot.OFF_HAND,
            EquipmentSlot.FEET,
            EquipmentSlot.LEGS,
            EquipmentSlot.CHEST,
            EquipmentSlot.HEAD);

    private final PaperItemIdentityCodec identityCodec = new PaperItemIdentityCodec();

    PaperTemplateUpdateScanner.Candidate scan(Entity entity) {
        List<PaperTemplateUpdateScanner.Candidate> candidates = scanAll(entity);
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    List<PaperTemplateUpdateScanner.Candidate> scanAll(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (!entity.isValid() || entity.isDead()) {
            return List.of();
        }
        if (entity instanceof ArmorStand armorStand) {
            return scanArmorStand(armorStand);
        }
        PaperEntityTemplateUpdateReference reference =
                PaperEntityTemplateUpdateReference.capture(entity);
        PaperTemplateUpdateScanner.Candidate candidate = scan(reference, entity);
        return candidate == null ? List.of() : List.of(candidate);
    }

    private List<PaperTemplateUpdateScanner.Candidate> scanArmorStand(ArmorStand armorStand) {
        List<PaperTemplateUpdateScanner.Candidate> candidates = new ArrayList<>();
        for (EquipmentSlot slot : ARMOR_STAND_SLOTS) {
            PaperEntityTemplateUpdateReference reference =
                    PaperEntityTemplateUpdateReference.armorStand(armorStand, slot);
            PaperTemplateUpdateScanner.Candidate candidate = scan(reference, armorStand);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return List.copyOf(candidates);
    }

    private PaperTemplateUpdateScanner.Candidate scan(
            PaperEntityTemplateUpdateReference reference,
            Entity entity) {
        if (reference == null) {
            return null;
        }
        ItemStack item = reference.read(entity);
        if (item.getType().isAir()) {
            return null;
        }
        ItemIdentityReadResult result = identityCodec.readIdentity(item);
        if (!(result instanceof ItemIdentityReadResult.Tracked tracked)) {
            return null;
        }
        return new PaperTemplateUpdateScanner.Candidate(tracked.identity(), reference);
    }
}
