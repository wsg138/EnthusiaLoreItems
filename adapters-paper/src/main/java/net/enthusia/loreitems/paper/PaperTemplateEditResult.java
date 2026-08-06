package net.enthusia.loreitems.paper;

import java.util.Objects;
import org.bukkit.inventory.ItemStack;

record PaperTemplateEditResult(boolean accepted, ItemStack item, String detail) {
    PaperTemplateEditResult {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(detail, "detail");
        detail = detail.strip();
        if (detail.isEmpty()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
        item = item.clone();
    }

    @Override
    public ItemStack item() {
        return item.clone();
    }

    static PaperTemplateEditResult accepted(ItemStack item, String detail) {
        return new PaperTemplateEditResult(true, item, detail);
    }

    static PaperTemplateEditResult rejected(ItemStack item, String detail) {
        return new PaperTemplateEditResult(false, item, detail);
    }
}
