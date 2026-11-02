package dev.lotus.intellij

import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Paths

/**
 * Extensions shipped inside a compiled library jar (an API you wrote and `mvn install`ed) must work in
 * projects that depend on it, where IntelliJ only sees class files, not sources.
 */
class LotusLibraryTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = LibraryDescriptor

    private object LibraryDescriptor : DefaultLightProjectDescriptor() {
        override fun getSdk(): Sdk = JavaSdk.getInstance().createJdk("test-jdk", System.getProperty("java.home"), false)

        /** The jar built by Gradle from src/apiFixture/java, i.e. compiled classes only, no sources. */
        override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
            super.configureModule(module, model, contentEntry)
            val jar = Paths.get(checkNotNull(System.getProperty("lotus.apiFixtureJar")) { "run via ./gradlew test" })
            PsiTestUtil.addLibrary(model, "my-api", jar.parent.toString(), jar.fileName.toString())
        }
    }

    fun `test extensions from a library jar resolve as real members`() {
        myFixture.configureByText(
            "App.java",
            """
            import com.acme.api.Wallet;
            import java.util.ArrayList;
            import java.util.List;
            class App {
                void run(Wallet wallet) {
                    boolean ok = wallet.canAfford(100);
                    String text = wallet.describe();
                    Wallet none = Wallet.empty();
                    List<String> names = new ArrayList<>();
                    String first = names.firstOrNull();
                    String s = "${'$'}{wallet.describe()} / ${'$'}{first}";
                    wallet.<error descr="Cannot resolve method 'spend' in 'Wallet'">spend</error>(5);
                }
            }
            """.trimIndent(),
        )
        myFixture.checkHighlighting()

        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "canAfford" }
        assertInstanceOf(call.resolveMethod(), LotusExtensionMethod::class.java)
    }

    fun `test extensions from a library jar show up in completion`() {
        myFixture.configureByText("App.java", "import com.acme.api.Wallet; class App { void run(Wallet w) { w.<caret> } }")
        val items = myFixture.completeBasic().map { it.lookupString }
        assertContainsElements(items, "canAfford", "describe", "getCoins")
    }
}
