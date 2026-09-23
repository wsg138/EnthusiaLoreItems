package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

class PaperGuiStyleTest {
    @Test
    void namesUseSemanticColorsWithoutDefaultItalics() {
        assertStyled(
                PaperGuiStyle.name("action", PaperGuiStyle.Tone.PRIMARY),
                NamedTextColor.AQUA);
        assertStyled(
                PaperGuiStyle.name("info", PaperGuiStyle.Tone.INFO),
                NamedTextColor.WHITE);
        assertStyled(
                PaperGuiStyle.name("confirm", PaperGuiStyle.Tone.POSITIVE),
                NamedTextColor.GREEN);
        assertStyled(
                PaperGuiStyle.name("warning", PaperGuiStyle.Tone.WARNING),
                NamedTextColor.GOLD);
        assertStyled(
                PaperGuiStyle.name("delete", PaperGuiStyle.Tone.DESTRUCTIVE),
                NamedTextColor.RED);
        assertStyled(
                PaperGuiStyle.name("back", PaperGuiStyle.Tone.NAVIGATION),
                NamedTextColor.GRAY);
        assertStyled(
                PaperGuiStyle.name("status", PaperGuiStyle.Tone.METADATA),
                NamedTextColor.GOLD);
    }

    @Test
    void loreAndInventoryTitlesAreNonItalicAndReadable() {
        assertStyled(PaperGuiStyle.lore("Revision: 7"), NamedTextColor.GRAY);
        assertStyled(PaperGuiStyle.inventoryTitle("Template management"), NamedTextColor.GOLD);
        assertEquals(
                TextDecoration.State.FALSE,
                PaperGuiStyle.lore("").decoration(TextDecoration.ITALIC));
    }

    private static void assertStyled(Component component, NamedTextColor expectedColor) {
        assertEquals(expectedColor, component.color());
        assertEquals(
                TextDecoration.State.FALSE,
                component.decoration(TextDecoration.ITALIC));
    }
}
