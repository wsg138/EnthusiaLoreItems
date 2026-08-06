package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.Set;
import org.bukkit.inventory.ItemStack;

/** Owning-thread typed editor for every WP-01 component path. */
final class PaperTemplateDraftEditor {
    private static final Set<String> COMMON_ACTIONS = Set.of(
            "material",
            "custom-name",
            "item-name",
            "lore",
            "enchant",
            "glint",
            "durability",
            "attribute",
            "item-model",
            "max-stack",
            "custom-model-data",
            "flags",
            "tooltip");

    private final PaperTemplateCommonEditor common = new PaperTemplateCommonEditor();
    private final PaperTemplateSpecializedEditor specialized =
            new PaperTemplateSpecializedEditor();

    PaperTemplateEditResult apply(ItemStack source, String action, String input) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(input, "input");
        return COMMON_ACTIONS.contains(action)
                ? common.apply(source, action, input)
                : specialized.apply(source, action, input);
    }
}
