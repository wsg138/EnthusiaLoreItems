package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class DistributionCampaignCommandCompletionTest {
    private Plugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = MockBukkit.getMock().addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void hidesRecipientStateCompletionsWithoutInspectPermission() {
        assertEquals(
                List.of(),
                DistributionCampaignCommandExecutor.recipientStateCompletions(player));
    }

    @Test
    void exposesRecipientStateCompletionsToInspectPermission() {
        player.addAttachment(
                plugin, DistributionCampaignCommandExecutor.INSPECT_PERMISSION, true);

        List<String> expected = new ArrayList<>();
        expected.add("all");
        for (CampaignRecipientState state : CampaignRecipientState.values()) {
            expected.add(state.name().toLowerCase(Locale.ROOT));
        }

        assertEquals(
                expected,
                DistributionCampaignCommandExecutor.recipientStateCompletions(player));
    }
}
