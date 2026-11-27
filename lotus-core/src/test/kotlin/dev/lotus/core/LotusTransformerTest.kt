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
        val log = object : LotusTransformer.Log { override fun warn(message: String) = println(message) }
        val result = LotusTransformer(listOf(dir.resolve("src")), emptyList(), log = log).transform(out)
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

    @Test
    fun `resolves receivers through Lombok-generated members and record accessors`() {
        source("demo/Player.java", "package demo; public class Player { public String getName() { return \"Steve\"; } }")
        source("demo/PlayerExtension.java", """
            package demo;
            import dev.lotus.*;
            @Extension(Player.class)
            public final class PlayerExtension {
                public static void heal(@This Player p, int amount) {}
            }
        """)
        source("demo/Handler.java", "package demo; public interface Handler { void apply(Player source, Player target, int level); }")
        // Fluent setters and getters, like `EnchantProvider.of().killed(...)`.
        source("demo/Provider.java", """
            package demo;
            import lombok.Data;
            import lombok.experimental.Accessors;
            @Data
            @Accessors(fluent = true)
            public class Provider {
                private Handler killed;
                private Player owner;
                public static Provider of() { return new Provider(); }
            }
        """)
        source("demo/Box.java", """
            package demo;
            import lombok.*;
            import java.util.function.Consumer;
            @Builder
            @Getter
            public class Box {
                private final Consumer<Player> onOpen;
                private final String label;
            }
        """)
        source("demo/Holder.java", """
            package demo;
            import lombok.RequiredArgsConstructor;
            import java.util.function.Consumer;
            @RequiredArgsConstructor
            public class Holder {
                private final Consumer<Player> action;
            }
        """)
        // Lambdas in enum constants, with the Lombok constructor declared in the same file.
        source("demo/Kind.java", """
            package demo;
            import lombok.Getter;
            import lombok.RequiredArgsConstructor;
            import java.util.function.Consumer;
            @Getter
            @RequiredArgsConstructor
            public enum Kind {
                RANSOM("Ransom", Provider.of().killed((source, _, level) -> {
                    if (level > 1)
                        source.heal(level);
                })),
                HEALER("Healer", Provider.of().killed((source, target, level) -> target.heal(1)));

                private final String label;
                private final Provider provider;

                void run(Kind other) { other.getProvider().owner().heal(2); }
            }
        """)
        source("demo/Effect.java", """
            package demo;
            import lombok.RequiredArgsConstructor;
            import java.util.function.Consumer;
            @RequiredArgsConstructor
            public enum Effect {
                BURN(p -> p.heal(5));
                private final Consumer<Player> apply;
            }
        """)
        source("demo/Info.java", "package demo; public record Info(int level, Player target) {}")
        source("demo/Main.java", """
            package demo;
            class Main {
                void info(Info info) { info.target().heal(8); }
                java.util.function.Consumer<Info> c = info -> info.target().heal(7);
                void run(Player player) {
                    Provider.of().owner(player).killed((s, t, l) -> s.heal(l));
                    Box box = Box.builder().label("x").onOpen(p -> p.heal(3)).build();
                    box.getOnOpen();
                    new Holder(p -> p.heal(4));
                }
            }
        """)

        val (result, read) = transform()
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        val kind = read("demo/Kind.java")
        assertTrue("demo.PlayerExtension.heal(source, level);" in kind, kind)
        assertTrue("demo.PlayerExtension.heal(target, 1)" in kind, kind)
        assertTrue("demo.PlayerExtension.heal(other.getProvider().owner(), 2);" in kind, kind)
        assertTrue("BURN(p -> demo.PlayerExtension.heal(p, 5));" in read("demo/Effect.java"), read("demo/Effect.java"))
        val main = read("demo/Main.java")
        assertTrue("void info(Info info) { demo.PlayerExtension.heal(info.target(), 8); }" in main, main)
        assertTrue("c = info -> demo.PlayerExtension.heal(info.target(), 7);" in main, main)
        assertTrue("killed((s, t, l) -> demo.PlayerExtension.heal(s, l));" in main, main)
        assertTrue("onOpen(p -> demo.PlayerExtension.heal(p, 3))" in main, main)
        assertTrue("new Holder(p -> demo.PlayerExtension.heal(p, 4));" in main, main)

        // The generated members only exist for resolution; nothing of them reaches the output.
        assertEquals(dir.resolve("src/demo/Kind.java").readText().replace("source.heal(level)", "demo.PlayerExtension.heal(source, level)")
            .replace("target.heal(1)", "demo.PlayerExtension.heal(target, 1)")
            .replace("other.getProvider().owner().heal(2)", "demo.PlayerExtension.heal(other.getProvider().owner(), 2)"), kind)
        assertEquals(dir.resolve("src/demo/Provider.java").readText(), read("demo/Provider.java"))
    }
}
