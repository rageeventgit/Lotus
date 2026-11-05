package dev.lotus.intellij

import com.intellij.codeInspection.deadCode.UnusedDeclarationInspection
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.ide.highlighter.JavaHighlightingColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class LotusPluginTest : LightJavaCodeInsightFixtureTestCase() {

    /** Use the JDK running the tests, so java.lang/java.util resolve like in a real project. */
    override fun getProjectDescriptor(): LightProjectDescriptor = object : DefaultLightProjectDescriptor() {
        override fun getSdk(): Sdk = JavaSdk.getInstance().createJdk("test-jdk", System.getProperty("java.home"), false)
    }

    override fun setUp() {
        super.setUp()
        myFixture.addClass("package dev.lotus; public @interface Extension { Class<?> value(); }")
        myFixture.addClass("package dev.lotus; public @interface This {}")
        myFixture.addClass("package dev.lotus; public @interface DisableTemplates {}")
        myFixture.addClass(
            """
            package game;
            public class Player {
                public String getName() { return "Steve"; }
                public void heal(int a, int b) {}
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package game;
            import dev.lotus.*;
            @Extension(Player.class)
            public final class PlayerExtension {
                public static void heal(@This Player p, double amount) {}
                public static boolean isAlive(@This Player p) { return true; }
                public static Player create(String name) { return new Player(); }
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package game;
            import dev.lotus.*;
            import java.util.Collection;
            @Extension(Collection.class)
            public final class CollectionExtensions {
                public static <T> T firstOrNull(@This Collection<T> c) { return null; }
                public static String shout(@This String s) { return s; }
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package game;
            import dev.lotus.*;
            @Extension(String.class)
            public final class StringExtensions {
                public static String shout(@This String s) { return s.toUpperCase(); }
            }
            """.trimIndent(),
        )
    }

    fun `test extension methods resolve as real members`() {
        myFixture.configureByText(
            "Main.java",
            """
            package game;
            import java.util.ArrayList;
            import java.util.List;
            class Main {
                void run(Player player) {
                    player.heal(5);
                    player.heal(1, 2);
                    boolean alive = player.isAlive();
                    Player other = Player.create("Alex");
                    String loud = "hi".shout();
                    List<String> names = new ArrayList<>();
                    String first = names.firstOrNull();
                    int wrong = names.<error descr="Incompatible types. Found: 'java.lang.String', required: 'int'">firstOrNull</error>();
                    player.<error descr="Cannot resolve method 'fly' in 'Player'">fly</error>();
                }
            }
            """.trimIndent(),
        )
        myFixture.checkHighlighting()

        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "isAlive" }
        val method = call.resolveMethod()
        assertInstanceOf(method, LotusExtensionMethod::class.java)
        assertEquals("PlayerExtension", ((method as LotusExtensionMethod).navigationElement as PsiMethod).containingClass?.name)
    }

    fun `test extension methods show up in completion`() {
        myFixture.configureByText("Main.java", "package game; class Main { void run(Player p) { p.<caret> } }")
        val items = myFixture.completeBasic().map { it.lookupString }
        assertContainsElements(items, "heal", "isAlive", "getName")
    }

    fun `test string templates are checked`() {
        myFixture.configureByText(
            "Main.java",
            """
            package game;
            class Main {
                void run(Player player, int coins) {
                    String ok = "Hi ${'$'}{player.getName()}, ${'$'}coins coins, alive=${'$'}{player.isAlive()} ${'$'}{\"x\".shout()}";
                    String bad = "Hi ${'$'}<error descr="Cannot resolve symbol 'nobody'">nobody</error>";
                    String unclosed = "<error descr="Unclosed string template: missing '}'">${'$'}{player</error>";
                    String price = "costs ${'$'}5";
                }
                @dev.lotus.DisableTemplates
                String off() { return "${'$'}{whatever}"; }
            }
            """.trimIndent(),
        )
        myFixture.checkHighlighting()
    }

    fun `test template references navigate and complete`() {
        myFixture.configureByText(
            "Main.java",
            "package game; class Main { void run(Player player) { String s = \"${'$'}{pla<caret>yer.getName()}\"; } }",
        )
        val target = myFixture.getReferenceAtCaretPositionWithAssertion().resolve()
        assertEquals("player", (target as com.intellij.psi.PsiParameter).name)

        myFixture.configureByText("Other.java", "package game; class Other { void run(Player player) { String s = \"${'$'}{player.is<caret>}\"; } }")
        val items = myFixture.completeBasic()?.map { it.lookupString }
        if (items != null) assertContainsElements(items, "isAlive") else assertTrue(myFixture.editor.document.text.contains("isAlive"))
    }

    fun `test template code gets Java highlighting`() {
        myFixture.configureByText(
            "Main.java",
            """
            package game;
            class Main {
                static final int MAX = 3;
                void run(Player player) {
                    int coins = 5;
                    String s = "${'$'}{player.getName()} ${'$'}{coins + 1} ${'$'}{MAX} ${'$'}{player.isAlive()} ${'$'}{\"x\".shout()} ${'$'}{coins > 2 ? \"rich\" : \"poor\"} ${'$'}{new StringBuilder()} ${'$'}{true} ${'$'}coins";
                }
            }
            """.trimIndent(),
        )
        val infos = myFixture.doHighlighting()
        val text = myFixture.editor.document.text
        val line = text.indexOf("String s =")
        val literal = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiLiteralExpression::class.java).first { it.text.contains("player") }
        val tokens = LotusAnnotator.codeTokens(literal).map { (range, key) -> range.shiftRight(literal.textRange.startOffset) to key }
        val scheme = EditorColorsManager.getInstance().globalScheme

        /** The color key of the [occurrence]th match of [snippet] on the template line, checked against the editor. */
        fun keyOf(snippet: String, occurrence: Int = 0): TextAttributesKey {
            var start = text.indexOf(snippet, line)
            repeat(occurrence) { start = text.indexOf(snippet, start + 1) }
            val expected = TextRange(start, start + snippet.length)
            val key = tokens.firstOrNull { it.first == expected }?.second
            assertNotNull("no token for '$snippet' at $expected; tokens: ${tokens.joinToString { "${it.first.substring(text)}=${it.second.externalName}" }}", key)
            // The editor shows exactly those colors, never the green of the surrounding string.
            val info = infos.firstOrNull { it.startOffset == expected.startOffset && it.endOffset == expected.endOffset && it.forcedTextAttributes != null }
            assertNotNull("'$snippet' isn't highlighted in the editor", info)
            assertEquals(LotusAnnotator.codeAttributes(scheme, key!!), info!!.forcedTextAttributes)
            assertNotNull(info.forcedTextAttributes.foregroundColor)
            return key
        }

        assertEquals(JavaHighlightingColors.PARAMETER_ATTRIBUTES, keyOf("player"))
        assertEquals(JavaHighlightingColors.METHOD_CALL_ATTRIBUTES, keyOf("getName"))
        assertEquals(JavaHighlightingColors.LOCAL_VARIABLE_ATTRIBUTES, keyOf("coins"))
        assertEquals(JavaHighlightingColors.NUMBER, keyOf("1"))
        assertEquals(JavaHighlightingColors.STATIC_FINAL_FIELD_ATTRIBUTES, keyOf("MAX"))
        assertEquals(LotusColors.EXTENSION_CALL, keyOf("isAlive"))
        assertEquals(LotusColors.EXTENSION_CALL, keyOf("shout"))
        // Escaped strings inside a template map back onto their source text.
        assertEquals(JavaHighlightingColors.STRING, keyOf("\\\"rich\\\""))
        assertEquals(JavaHighlightingColors.LOCAL_VARIABLE_ATTRIBUTES, keyOf("coins", occurrence = 1))
        assertEquals(JavaHighlightingColors.KEYWORD, keyOf("new"))
        assertEquals(JavaHighlightingColors.CLASS_NAME_ATTRIBUTES, keyOf("StringBuilder"))
        assertEquals(JavaHighlightingColors.KEYWORD, keyOf("true"))
        // `$coins`, the short form.
        assertEquals(JavaHighlightingColors.LOCAL_VARIABLE_ATTRIBUTES, keyOf("coins", occurrence = 2))

        // References still work in templates that contain escapes.
        val escapedCoins = run { var i = text.indexOf("coins", line); repeat(1) { i = text.indexOf("coins", i + 1) }; i }
        assertInstanceOf(myFixture.file.findReferenceAt(escapedCoins)?.resolve(), PsiLocalVariable::class.java)
    }

    fun `test templates warn when the build won't run Lotus`() {
        val setup = object : LotusBuildSetup {
            override fun problem(file: PsiFile) = LotusBuildSetup.Problem("test reason", null)
        }
        ExtensionTestUtil.maskExtensions(LotusBuildSetup.EP, listOf(setup), testRootDisposable)
        myFixture.configureByText(
            "Main.java",
            "package game; class Main { String a(int coins) { return \"${'$'}coins coins\"; } String b() { return \"plain\"; } }",
        )
        val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING).filter { it.description?.contains("won't be converted") == true }
        assertEquals("This string template won't be converted: test reason", warnings.single().description)
        assertEquals("\"${'$'}coins coins\"", myFixture.editor.document.text.substring(warnings[0].startOffset, warnings[0].endOffset))
    }

    fun `test lotus icon loads`() {
        // The pixel size depends on the machine's UI scale, so only check that the icon is real and square.
        assertTrue(LotusIcons.Lotus.iconWidth > 0)
        assertEquals(LotusIcons.Lotus.iconWidth, LotusIcons.Lotus.iconHeight)
        for (path in listOf("/icons/lotus.svg", "/icons/lotus_dark.svg", "/META-INF/pluginIcon.svg", "/META-INF/pluginIcon_dark.svg")) {
            assertNotNull(path, javaClass.getResource(path))
        }
    }

    fun `test variables used only in templates are not unused`() {
        myFixture.enableInspections(UnusedDeclarationInspection(true))
        myFixture.configureByText(
            "Main.java",
            """
            package game;
            public class Main {
                public static void main(String[] args) {
                    String name = "Steve";
                    System.out.println("Hello ${'$'}name, ${'$'}{args.length} args");
                }
            }
            """.trimIndent(),
        )
        myFixture.checkHighlighting()
    }
}
