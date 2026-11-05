import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.lotus"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(25)
}

// IntelliJ 2024.3 - 2025.x run on Java 21, so the plugin must not need anything newer. Set on the task
// because the platform plugin otherwise derives the target from the (Java 25) IDE being built against.
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

// The plugin's own version, for writing a matching lotus-maven-plugin into a pom.xml.
tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("dev/lotus/intellij/lotus.properties") { expand("version" to pluginVersion) }
}

sourceSets {
    main {
        // The template parser is shared verbatim with the Maven transformer so both agree on syntax.
        kotlin.srcDir("../lotus-core/src/main/kotlin/dev/lotus/core/template")
    }
    // A tiny "API you wrote yourself" that ships extensions, packed as a jar for LotusLibraryTest.
    create("apiFixture")
}

val apiFixtureJar = tasks.register<Jar>("apiFixtureJar") {
    archiveBaseName = "my-api"
    destinationDirectory = layout.buildDirectory.dir("apiFixture")
    from(sourceSets["apiFixture"].output)
}

tasks.test {
    dependsOn(apiFixtureJar)
    val jar = apiFixtureJar.flatMap { it.archiveFile }
    jvmArgumentProviders += CommandLineArgumentProvider { listOf("-Dlotus.apiFixtureJar=${jar.get().asFile.absolutePath}") }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")

    intellijPlatform {
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
        val localPath = providers.gradleProperty("platformLocalPath").orNull
        if (!localPath.isNullOrBlank()) {
            local(localPath)
        } else {
            // From the IntelliJ Maven repository: there are no separate Community installers since 2025.3.
            create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion")) {
                useInstaller = false
            }
        }
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.idea.maven")
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    // `./gradlew verifyPlugin` checks API compatibility with the oldest supported IDE and the current one.
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("verifyOldestVersion"))
            create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion")) {
                useInstaller = false
            }
        }
    }
    buildSearchableOptions = false
    // No GUI forms to compile, and skipping it avoids depending on a full JDK layout.
    instrumentCode = false
}
