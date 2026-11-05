package dev.lotus.intellij.maven

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class LotusPomTest : BasePlatformTestCase() {

    private fun pom(plugin: String, extra: String = "") = """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>a</groupId>
            <artifactId>b</artifactId>
            <version>1</version>
            <build>
                $extra
                <plugins>
                    $plugin
                </plugins>
            </build>
        </project>
    """.trimIndent()

    private fun lotusWarnings() =
        myFixture.doHighlighting(HighlightSeverity.WARNING).filter { it.description?.startsWith("Lotus never runs") == true }

    private fun compact() = myFixture.editor.document.text.replace(Regex("\\s+"), "")

    fun `test goals directly under plugin are flagged and fixed`() {
        myFixture.configureByText(
            "pom.xml",
            pom(
                """
                <plugin>
                    <groupId>dev.lotus</groupId>
                    <artifactId>lotus-<caret>maven-plugin</artifactId>
                    <version>1.0.0</version>
                    <goals>
                        <goal>transform</goal>
                        <goal>transform-test</goal>
                    </goals>
                </plugin>
                """,
            ),
        )
        val warning = lotusWarnings().single()
        assertTrue(warning.description, warning.description.contains("ignores <goals> placed directly under <plugin>"))
        assertEquals("<artifactId>lotus-maven-plugin</artifactId>", myFixture.editor.document.text.substring(warning.startOffset, warning.endOffset))

        myFixture.launchAction(myFixture.findSingleIntention("Fix lotus-maven-plugin in pom.xml"))
        assertTrue(compact(), compact().contains(
            "<version>1.0.0</version><executions><execution><goals><goal>transform</goal><goal>transform-test</goal></goals></execution></executions></plugin>",
        ))
        assertEmpty(lotusWarnings())
    }

    fun `test missing transform goal is added to the existing execution`() {
        myFixture.configureByText(
            "pom.xml",
            pom(
                """
                <plugin>
                    <groupId>dev.lotus</groupId>
                    <artifactId>lotus-<caret>maven-plugin</artifactId>
                    <version>1.0.0</version>
                    <executions>
                        <execution>
                            <id>lotus</id>
                            <goals>
                                <goal>transform-test</goal>
                            </goals>
                        </execution>
                    </executions>
                </plugin>
                """,
            ),
        )
        assertEquals("Lotus never runs: lotus-maven-plugin has no execution with the 'transform' goal.", lotusWarnings().single().description)
        myFixture.launchAction(myFixture.findSingleIntention("Fix lotus-maven-plugin in pom.xml"))
        assertTrue(compact(), compact().contains(
            "<execution><id>lotus</id><goals><goal>transform-test</goal><goal>transform</goal></goals></execution></executions>",
        ))
        assertEquals(1, Regex("<execution>").findAll(compact()).count())
    }

    fun `test a bound plugin and pluginManagement are not flagged`() {
        myFixture.configureByText(
            "pom.xml",
            pom(
                """
                <plugin>
                    <groupId>dev.lotus</groupId>
                    <artifactId>lotus-maven-plugin</artifactId>
                    <version>1.0.0</version>
                    <executions><execution><goals><goal>transform</goal></goals></execution></executions>
                </plugin>
                """,
                extra = "<pluginManagement><plugins><plugin><groupId>dev.lotus</groupId><artifactId>lotus-maven-plugin</artifactId></plugin></plugins></pluginManagement>",
            ),
        )
        assertEmpty(lotusWarnings())
    }

    fun `test plugin is added with the annotations version`() {
        myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>a</groupId>
                <artifactId>b</artifactId>
                <version>1</version>
                <dependencies>
                    <dependency>
                        <groupId>dev.lotus</groupId>
                        <artifactId>lotus-annotations</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
            </project>
            """.trimIndent(),
        )
        WriteCommandAction.runWriteCommandAction(project) { LotusPom.addPlugin(myFixture.file as XmlFile) }
        assertTrue(compact(), compact().contains(
            "</dependencies><build><plugins><plugin><groupId>dev.lotus</groupId><artifactId>lotus-maven-plugin</artifactId><version>1.0.0</version>" +
                "<executions><execution><goals><goal>transform</goal><goal>transform-test</goal></goals></execution></executions></plugin></plugins></build></project>",
        ))
        assertNotNull(LotusPom.pluginTag(myFixture.file as XmlFile))
    }
}
