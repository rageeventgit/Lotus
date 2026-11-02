package dev.lotus.core

import dev.lotus.core.template.TemplateParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class LotusTransformerTest {

    @TempDir
    lateinit var dir: Path

    private fun source(path: String, text: String) {
        val file = dir.resolve("src").resolve(path)
        file.parent.createDirectories()
        file.writeText(text.trimIndent())
    }

    private fun transform(): Pair<LotusTransformer.Result, (String) -> String> {
        val out = dir.resolve("out")
        val result = LotusTransformer(listOf(dir.resolve("src")), emptyList()).transform(out)
        return result to { path: String -> out.resolve(path).readText() }
    }

    @Test
    fun `parses templates`() {
        val parts = TemplateParser.parse("Hi \$name, you have \${coins * 2} coins")
        assertEquals(
            listOf("Hi ", "name", ", you have ", "coins * 2", " coins"),
            parts.map { if (it is TemplateParser.Text) it.raw else (it as TemplateParser.Expression).source },
        )
        assertTrue(TemplateParser.parse("costs $5 or $").isEmpty())
        assertEquals("m.get(\"k\")", (TemplateParser.parse("\${m.get(\\\"k\\\")}")[0] as TemplateParser.Expression).source)
    }

    @Test
    fun `rewrites extension calls and templates`() {
        source("demo/Player.java", """
            package demo;
            public class Player {
                public String getName() { return "Steve"; }
                public void heal(int a, int b) {}
            }
        """)
        source("demo/PlayerExtension.java", """
            package demo;
            import dev.lotus.Extension;
            import dev.lotus.This;
            @Extension(Player.class)
            public final class PlayerExtension {
                public static void heal(@This Player p, int amount) {}
                public static String shout(@This Player p) { return p.getName().toUpperCase(); }
                public static Player create(String name) { return new Player(); }
            }
        """)
        source("demo/StringExt.java", """
            package demo;
            import dev.lotus.*;
            @Extension(String.class)
            public final class StringExt {
                public static String twice(@This String s) { return s + s; }
            }
        """)
        source("demo/Main.java", """
            package demo;
            public class Main {
                void run(Player player) {
                    player.heal(5);
                    player.heal(1, 2);
                    String s = player.shout().twice();
                    Player other = Player.create("Alex");
                    System.out.println("Hello ${'$'}{player.shout()} and ${'$'}s!");
                }
            }
        """)

        val (result, read) = transform()
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        val main = read("demo/Main.java")
        assertTrue("demo.PlayerExtension.heal(player, 5);" in main, main)
        assertTrue("player.heal(1, 2);" in main, main)
        assertTrue("demo.StringExt.twice(demo.PlayerExtension.shout(player))" in main, main)
        assertTrue("demo.PlayerExtension.create(\"Alex\")" in main, main)
        assertTrue("(\"Hello \" + demo.PlayerExtension.shout(player) + \" and \" + s + \"!\")" in main, main)
        // Line numbers are preserved.
        assertEquals(read("demo/Main.java").lines().size, dir.resolve("src/demo/Main.java").readText().lines().size)
    }

    @Test
    fun `rewrites extension calls that only appear inside templates`() {
        source("demo/Entity.java", "package demo; public interface Entity { String getName(); }")
        source("demo/EntityExtension.java", """
            package demo;
            import dev.lotus.*;
            @Extension(Entity.class)
            public final class EntityExtension {
                public static String tag(@This Entity e) { return "[" + e.getName() + "]"; }
            }
        """)
        source("demo/App.java", """
            package demo;
            class App {
                String run(Entity e) { return "${'$'}{e.tag()} spawned"; }
            }
        """)

        val (result, read) = transform()
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        val app = read("demo/App.java")
        assertTrue("(demo.EntityExtension.tag(e) + \" spawned\")" in app || "(\"\" + demo.EntityExtension.tag(e) + \" spawned\")" in app, app)
    }
}
