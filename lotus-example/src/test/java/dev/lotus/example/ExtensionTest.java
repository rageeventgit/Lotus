package dev.lotus.example;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Main-source extensions are found in target/classes and work in tests too. */
class ExtensionTest {

    @Test
    void instanceAndStaticExtensions() {
        Player player = Player.create("Alex");
        player.setHealth(1);
        player.heal(100);
        assertEquals(20.0, player.getHealth());
        assertTrue(player.isAlive());
    }

    @Test
    void templatesAndGenerics() {
        String name = "Alex";
        List<Integer> numbers = Arrays.asList(3, 1, 4);
        Integer first = numbers.firstOrNull();
        assertEquals("ALEX! has 3 first", "${name.shout()} has $first first");
    }
}
