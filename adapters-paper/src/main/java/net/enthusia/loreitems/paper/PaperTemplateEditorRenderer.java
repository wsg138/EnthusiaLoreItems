package net.enthusia.loreitems.paper;

import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.item;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.enthusia.loreitems.application.TemplateManagementSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

final class PaperTemplateEditorRenderer {
    static final int MANAGEMENT_EDIT = 29;
    static final int MANAGEMENT_REPLACE = 31;
    static final int MANAGEMENT_INSTANCES = 33;
    static final int MANAGEMENT_PURGE = 37;
    static final int MANAGEMENT_DELETE = 41;
    static final int MANAGEMENT_BACK = 45;
    static final int MANAGEMENT_REFRESH = 49;
    static final int EDITOR_CANCEL = 45;
    static final int EDITOR_PREVIEW = 49;
    static final int PREVIEW_BACK = 29;
    static final int PREVIEW_CONFIRM = 31;
    static final int PREVIEW_CANCEL = 33;

    private static final int SIZE = 54;
    private static final Map<Integer, ActionSpec> EDITOR_ACTIONS = actions();

    void showManagement(
            Player player,
            TemplateManagementSnapshot snapshot,
            ItemStack preview,
            int returnPage) {
        PaperTemplateEditorView view = PaperTemplateEditorView.management(snapshot, returnPage);
        Inventory inventory = create(view, "Template management");
        populateManagementSummary(inventory, snapshot, preview);
        populateManagementActions(player, inventory);
        player.openInventory(inventory);
    }

    private static void populateManagementSummary(
            Inventory inventory,
            TemplateManagementSnapshot snapshot,
            ItemStack preview) {
        inventory.setItem(13, preview.clone());
        inventory.setItem(22, item(
                Material.CLOCK,
                snapshot.definition().displayName(),
                PaperGuiStyle.Tone.METADATA,
                List.of(
                        "Key: " + snapshot.definition().key().value(),
                        "Current revision: " + snapshot.definition().currentRevision().value(),
                        "Active instances: " + snapshot.activeInstanceCount(),
                        "Open anomalies: " + snapshot.anomalyCount(),
                        "Pending updates: " + snapshot.pendingUpdateCount(),
                        "",
                        snapshot.rolloutActive() ? "Rollout: active" : "Rollout: idle")));
    }

    private static void populateManagementActions(Player player, Inventory inventory) {
        populateStandardManagementActions(inventory);
        populateDestructiveManagementActions(player, inventory);
        populateManagementNavigation(inventory);
    }

    private static void populateStandardManagementActions(Inventory inventory) {
        inventory.setItem(MANAGEMENT_EDIT, item(
                Material.WRITABLE_BOOK,
                "Edit template",
                PaperGuiStyle.Tone.PRIMARY,
                List.of("Create a private draft; nothing is saved until confirmation.")));
        inventory.setItem(MANAGEMENT_REPLACE, item(
                Material.STRUCTURE_VOID,
                "Replace from held item",
                PaperGuiStyle.Tone.PRIMARY,
                List.of(
                        "Copy all supported item components from your held item.",
                        "LoreItems identity and stackability are stripped.",
                        "A preview and confirmation are required.")));
        inventory.setItem(MANAGEMENT_INSTANCES, item(
                Material.PLAYER_HEAD,
                "Browse instances",
                PaperGuiStyle.Tone.PRIMARY,
                List.of("Open tracked instances, holders, and location evidence.")));
    }

    private static void populateDestructiveManagementActions(
            Player player, Inventory inventory) {
        if (player.hasPermission(LoreItemsDestructiveCommandExecutor.PURGE_PERMISSION)) {
            inventory.setItem(MANAGEMENT_PURGE, item(
                    Material.LAVA_BUCKET,
                    "Purge every instance",
                    PaperGuiStyle.Tone.DESTRUCTIVE,
                    List.of(
                            "Preview physical removal of every tracked copy.",
                            "The definition and template remain active.",
                            "A separate fixed confirmation is required.")));
        }
        if (player.hasPermission(LoreItemsDestructiveCommandExecutor.DELETE_PERMISSION)) {
            inventory.setItem(MANAGEMENT_DELETE, item(
                    Material.TNT,
                    "Delete definition and items",
                    PaperGuiStyle.Tone.DESTRUCTIVE,
                    List.of(
                            "Preview deletion of this definition and all tracked copies.",
                            "Returning copies remain scheduled for removal.",
                            "A separate fixed confirmation is required.")));
        }
    }

    private static void populateManagementNavigation(Inventory inventory) {
        inventory.setItem(MANAGEMENT_BACK, item(
                Material.ARROW,
                "Back to definitions",
                PaperGuiStyle.Tone.NAVIGATION,
                List.of("Return to the same definition page.")));
        inventory.setItem(MANAGEMENT_REFRESH, item(
                Material.COMPASS,
                "Refresh status",
                PaperGuiStyle.Tone.INFO,
                List.of("Reload revision, instance, anomaly, and rollout counts.")));
    }

    void showEditor(Player player, PaperTemplateEditorSession session) {
        PaperTemplateEditorView view = PaperTemplateEditorView.editor(
                session.snapshot, session.sessionId, session.returnPage);
        Inventory inventory = create(view, "Template editor");
        inventory.setItem(4, session.draft.clone());
        EDITOR_ACTIONS.forEach((slot, action) -> inventory.setItem(
                slot,
                item(
                        action.icon(),
                        action.title(),
                        PaperGuiStyle.Tone.PRIMARY,
                        action.help())));
        inventory.setItem(EDITOR_CANCEL, item(
                Material.BARRIER,
                "Cancel draft",
                PaperGuiStyle.Tone.NAVIGATION,
                List.of("Discard every unconfirmed edit.")));
        inventory.setItem(EDITOR_PREVIEW, item(
                Material.LIME_CONCRETE,
                "Preview and confirm",
                PaperGuiStyle.Tone.POSITIVE,
                List.of("Review the complete draft before creating a revision.")));
        player.openInventory(inventory);
    }

    void showPreview(Player player, PaperTemplateEditorSession session) {
        PaperTemplateEditorView view = PaperTemplateEditorView.preview(
                session.snapshot, session.sessionId, session.returnPage);
        Inventory inventory = create(view, "Confirm template revision");
        inventory.setItem(13, session.before.clone());
        inventory.setItem(15, session.draft.clone());
        inventory.setItem(22, item(
                Material.BOOK,
                "Revision " + session.snapshot.definition().currentRevision().value()
                        + " → " + session.snapshot.definition().currentRevision().next().value(),
                PaperGuiStyle.Tone.METADATA,
                List.of(
                        "Left: current template",
                        "Right: complete draft",
                        "",
                        "Confirmation creates one immutable revision",
                        "and one durable rollout for every active instance.")));
        inventory.setItem(PREVIEW_BACK, item(
                Material.ARROW,
                "Back to editor",
                PaperGuiStyle.Tone.NAVIGATION,
                List.of("Continue editing the draft.")));
        inventory.setItem(PREVIEW_CONFIRM, item(
                Material.LIME_CONCRETE,
                "Confirm revision",
                PaperGuiStyle.Tone.POSITIVE,
                List.of("Persist the revision and rollout atomically.")));
        inventory.setItem(PREVIEW_CANCEL, item(
                Material.BARRIER,
                "Cancel draft",
                PaperGuiStyle.Tone.NAVIGATION,
                List.of("Discard the draft without changing the template.")));
        player.openInventory(inventory);
    }

    static ActionSpec action(int slot) {
        return EDITOR_ACTIONS.get(slot);
    }

    private static Inventory create(PaperTemplateEditorView view, String title) {
        Inventory inventory = Bukkit.createInventory(view, SIZE, PaperGuiStyle.inventoryTitle(title));
        view.attach(inventory);
        return inventory;
    }

    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private static Map<Integer, ActionSpec> actions() {
        Map<Integer, ActionSpec> actions = new LinkedHashMap<>();
        add(actions, 9, "material", Material.STONE, "Base material",
                "submit minecraft:diamond_sword");
        add(actions, 10, "custom-name", Material.NAME_TAG, "Custom name",
                "submit clear | literal <text> | solid <hex> <text> | gradient <colors> <text>");
        add(actions, 11, "item-name", Material.PAPER, "Item name",
                "submit clear | literal/solid/gradient ...");
        add(actions, 12, "lore", Material.WRITABLE_BOOK, "Lore lines",
                "submit add/edit/remove/move/clear ...");
        add(actions, 13, "enchant", Material.ENCHANTED_BOOK, "Enchantments",
                "submit set/remove/clear/tooltip ...");
        add(actions, 14, "glint", Material.GLOWSTONE_DUST, "Glint override",
                "submit true | false | unset");
        add(actions, 15, "durability", Material.ANVIL, "Damage and unbreakable",
                "submit damage <value> | unbreakable true|false");
        add(actions, 16, "attribute", Material.IRON_CHESTPLATE, "Attributes",
                "submit set/remove/clear ... stable modifier key required");
        add(actions, 17, "item-model", Material.ITEM_FRAME, "Item model",
                "submit <namespaced-key> | clear");
        add(actions, 18, "max-stack", Material.BUNDLE, "Maximum stack size",
                "submit 1 (tracked items are always normalized to one)");
        add(actions, 19, "custom-model-data", Material.COMMAND_BLOCK, "Custom model data",
                "submit floats/flags/strings/colors ... | clear");
        add(actions, 20, "dye", Material.LEATHER_CHESTPLATE, "Dyed color",
                "submit #RRGGBB | clear");
        add(actions, 21, "potion", Material.POTION, "Potion components",
                "submit base/set-effect/remove-effect/clear-effects/color/clear-color ...");
        add(actions, 23, "trim", Material.NETHERITE_CHESTPLATE, "Armor trim",
                "submit <material-key> <pattern-key> | clear");
        add(actions, 24, "banner", Material.WHITE_BANNER, "Banner patterns",
                "submit add/set/remove/clear ...");
        add(actions, 25, "profile", Material.PLAYER_HEAD, "Player profile",
                "submit <uuid> [name] | clear");
        add(actions, 26, "firework", Material.FIREWORK_ROCKET, "Firework effects",
                "submit power/add/remove/clear (rocket) or set/clear (star)");
        add(actions, 27, "flags", Material.REDSTONE_TORCH, "Item flags",
                "submit add <flag> | remove <flag> | clear");
        add(actions, 28, "tooltip", Material.KNOWLEDGE_BOOK, "Tooltip controls",
                "submit hide true|false | style <key|clear>");
        return Map.copyOf(actions);
    }

    private static void add(
            Map<Integer, ActionSpec> actions,
            int slot,
            String action,
            Material icon,
            String title,
            String help) {
        actions.put(slot, new ActionSpec(action, icon, title, List.of(help)));
    }

    record ActionSpec(String action, Material icon, String title, List<String> help) {
        ActionSpec {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(icon, "icon");
            Objects.requireNonNull(title, "title");
            help = List.copyOf(help);
        }
    }
}
