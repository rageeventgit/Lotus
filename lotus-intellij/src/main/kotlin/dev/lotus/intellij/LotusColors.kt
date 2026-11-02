package dev.lotus.intellij

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

object LotusColors {
    val EXTENSION_CALL: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("LOTUS_EXTENSION_CALL", DefaultLanguageHighlighterColors.STATIC_METHOD)
    val TEMPLATE_ENTRY: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("LOTUS_TEMPLATE_ENTRY", DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE)
    val TEMPLATE_EXPRESSION: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("LOTUS_TEMPLATE_EXPRESSION", HighlighterColors.TEXT)
}

/** Settings | Editor | Color Scheme | Lotus. */
class LotusColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName() = "Lotus"
    override fun getIcon(): Icon = LotusIcons.Lotus
    override fun getHighlighter(): SyntaxHighlighter =
        SyntaxHighlighterFactory.getSyntaxHighlighter(JavaFileType.INSTANCE, null, null) ?: PlainSyntaxHighlighter()

    override fun getAttributeDescriptors() = arrayOf(
        AttributesDescriptor("Extension method call", LotusColors.EXTENSION_CALL),
        AttributesDescriptor("String template//Entry delimiters (\$, \${, })", LotusColors.TEMPLATE_ENTRY),
        // Code inside templates uses the Java colors; this is the fallback for anything Java leaves uncolored.
        AttributesDescriptor("String template//Code (base color under Java highlighting)", LotusColors.TEMPLATE_EXPRESSION),
    )

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDemoText() = """
        class Demo {
            void greet(Player player) {
                player.<ext>heal</ext>(5);
                player.sendMessage("Hello <entry>${'$'}{</entry><expr>player.getName()</expr><entry>}</entry>, you have <entry>${'$'}</entry><expr>coins</expr> coins");
            }
        }
    """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap() = mapOf(
        "ext" to LotusColors.EXTENSION_CALL,
        "entry" to LotusColors.TEMPLATE_ENTRY,
        "expr" to LotusColors.TEMPLATE_EXPRESSION,
    )
}
