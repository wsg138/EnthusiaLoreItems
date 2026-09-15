package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class PaperCachedPlayerIdentityResolverTest {
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void rejectsCachedPlayerLookupAwayFromServerThread() {
        PaperCachedPlayerIdentityResolver resolver = new PaperCachedPlayerIdentityResolver();

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> CompletableFuture.supplyAsync(() -> resolver.resolve("NeverJoined"))
                        .join());

        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }
}
