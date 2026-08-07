package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class LoreItemsAdministrationSaturationTest {
    private LoreItemsAdministrationCommandExecutor executor;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        Plugin plugin = MockBukkit.createMockPlugin();
        executor = new LoreItemsAdministrationCommandExecutor(plugin, 200);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void administrationQueriesCapAtThirtyTwoAndReleaseCapacityOnCompletion()
            throws ReflectiveOperationException {
        Class<?> actorType = Class.forName(
                LoreItemsAdministrationCommandExecutor.class.getName() + "$CommandActor");
        Constructor<?> actorConstructor = actorType.getDeclaredConstructor(UUID.class);
        actorConstructor.setAccessible(true);
        Method beginQuery = LoreItemsAdministrationCommandExecutor.class.getDeclaredMethod(
                "beginQuery", actorType);
        Method finishQuery = LoreItemsAdministrationCommandExecutor.class.getDeclaredMethod(
                "finishQuery", actorType);
        beginQuery.setAccessible(true);
        finishQuery.setAccessible(true);

        Object firstActor = newActor(actorConstructor, 1L);
        assertTrue((Boolean) beginQuery.invoke(executor, firstActor));
        for (long index = 2L; index <= 32L; index++) {
            assertTrue((Boolean) beginQuery.invoke(executor, newActor(actorConstructor, index)));
        }
        Object overflowActor = newActor(actorConstructor, 33L);
        assertFalse((Boolean) beginQuery.invoke(executor, overflowActor));

        finishQuery.invoke(executor, firstActor);
        assertTrue((Boolean) beginQuery.invoke(executor, overflowActor));
    }

    private static Object newActor(Constructor<?> constructor, long value)
            throws ReflectiveOperationException {
        return constructor.newInstance(new UUID(0L, value));
    }
}
