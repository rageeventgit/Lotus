<img src="lotus-intellij/src/main/resources/META-INF/pluginIcon.svg" width="80" alt="Lotus logo">

# Lotus

Kotlin-style **extension methods** and **string templates** for Java, with a Maven plugin that
compiles them and an IntelliJ plugin that makes the IDE treat them as real code.

```java
@Extension(Player.class)
public final class PlayerExtension {
    public static void heal(@This Player player, double amount) { ... }   // player.heal(5)
    public static boolean isAlive(@This Player player) { ... }            // player.isAlive()
    public static Player create(String name) { ... }                      // Player.create("Steve")
}

Player player = Player.create("Steve");
player.heal(5);
player.sendMessage("Hello ${player.getName()}, alive: ${player.isAlive()}");
System.out.println("$name has $health health");
```

## Modules

| Module | Language | What it does |
| --- | --- | --- |
| `lotus-annotations` | Java | `@Extension`, `@This`, `@DisableTemplates`. Plain Java so Java users get zero extra dependencies. |
| `lotus-core` | Kotlin | Source-to-source transformer (JavaParser + symbol solver, ASM for scanning jars). |
| `lotus-maven-plugin` | Kotlin | `lotus:transform` / `lotus:transform-test` goals that run the transformer before `javac`. |
| `lotus-intellij` | Kotlin | IntelliJ plugin (separate Gradle build). |
| `lotus-example` | Java | A runnable example using every feature. |

## Features

**Extension methods**
- Instance extensions: the first parameter is annotated `@This`. `player.heal(5)` → `PlayerExtension.heal(player, 5)`.
- Static extensions: no `@This`. `Player.create("x")` → `PlayerExtension.create("x")`.
- Inherited, like Kotlin: an extension on `Collection` works on every `List`, `Set`, …
- Generics: `<T> T firstOrNull(@This Collection<T> c)` called on a `List<String>` returns `String`.
- Varargs, extensions on JDK types (`"hi".shout()`), extensions on `this` (unqualified calls inside a subclass).
- Method references: `names.stream().map(String::shout)`.
- A real member always wins over an extension with the same name and arity.
- Extension libraries: `@Extension` has CLASS retention, so extensions in dependency jars are picked up too.

**String templates**
- `"$name"` and `"${any.java(expression)}"` in ordinary string literals.
- Extension calls work inside templates: `"${name.shout()}"`.
- Strings inside a template expression must be escaped as usual in Java: `"${flag ? \"yes\" : \"no\"}"`.
- `$` not followed by an identifier or `{` stays literal (`"costs $5"`).
- Not applied in annotations, `case` labels or text blocks. Use `@DisableTemplates` on a class, method,
  field or local variable when you need a literal `${`.

**IntelliJ plugin**
- Extensions are injected as real members (via `PsiAugmentProvider`), so resolve, completion, parameter
  info, type inference, error highlighting, quick docs and Ctrl+Click all work. Ctrl+Click goes to the static method.
- Extension calls are drawn in their own color (customizable under *Settings → Editor → Color Scheme → Lotus*).
- Templates: the code inside `${...}` and `$name` gets full Java highlighting in your color scheme
  (keywords, numbers, strings, locals, parameters, fields, method and extension calls, class names), `$`/`${}`
  delimiters are highlighted, and unresolved symbols, unclosed templates and `void` expressions
  reported as errors, Ctrl+Click, Find Usages and Rename work inside templates, and completion pops up
  after `$`, `${` and `.`.
- No false "unused" warnings for extension classes or variables only read from templates.
- Validation of `@Extension` classes (`@This` must be first, method must be static, receiver type must fit).
- Lotus gutter icons linking extension classes/methods to the type they extend.

## Java compatibility

| Part | Works with |
| --- | --- |
| `lotus-annotations` | Projects compiling for Java 8 and newer (Java 8 bytecode) |
| `lotus-maven-plugin` | Maven 3.6.3+ running on JDK 8 and newer |
| Generated code | Plain Java 8 (static calls and `+` concatenation), whatever your project targets |
| IntelliJ plugin | IntelliJ IDEA 2024.3 and newer (Community and Ultimate) |

## Installing from GitHub Packages

Lotus is published to [GitHub Packages](https://github.com/rageeventgit/Lotus/packages), and the IntelliJ
plugin zip is attached to each [release](https://github.com/rageeventgit/Lotus/releases).

GitHub Packages needs a token to download Maven packages, even public ones. Create a
[personal access token (classic)](https://github.com/settings/tokens) with the `read:packages` scope and add it
to `~/.m2/settings.xml`:

```xml
<settings>
    <servers>
        <server>
            <id>github-lotus</id>
            <username>YOUR_GITHUB_USERNAME</username>
            <password>YOUR_TOKEN</password>
        </server>
    </servers>
</settings>
```

Then add the repository to your project's `pom.xml`. It's needed twice: once for the annotations
(a dependency) and once for the Maven plugin:

```xml
<repositories>
    <repository>
        <id>github-lotus</id>
        <url>https://maven.pkg.github.com/rageeventgit/Lotus</url>
    </repository>
</repositories>
<pluginRepositories>
    <pluginRepository>
        <id>github-lotus</id>
        <url>https://maven.pkg.github.com/rageeventgit/Lotus</url>
    </pluginRepository>
</pluginRepositories>
```

The `<id>` must be the same in `settings.xml` and `pom.xml`. Then add the dependency and plugin from
[Using it in a Maven project](#using-it-in-a-maven-project).

**Releasing a new version** (maintainers): bump the version in the POMs and `lotus-intellij/gradle.properties`,
push, and publish a GitHub release (e.g. tag `v1.0.1`). The *Publish* workflow deploys the Maven artifacts
and attaches the IntelliJ plugin zip to the release. GitHub Packages doesn't allow overwriting a version.

## Adding Lotus to an existing project

1. **Get Lotus**: add the GitHub Packages repository (see [Installing from GitHub Packages](#installing-from-github-packages)),
   or run `mvn install` in this folder to put it in your local Maven repository.
2. **Add the dependency and plugin below** to your project's `pom.xml`. Your existing compiler settings
   (`maven.compiler.source`/`target`/`release` 8, 11, 17, …) stay as they are.
3. **Check existing strings for `$`.** Templates are on by default, so an existing string such as
   `"${player}"` (a config placeholder) or `"$HOME"` becomes a template: it fails to compile, or, worse, if
   a variable with that name is in scope, its value is silently inserted. Either
   annotate the class/method with `@DisableTemplates`, or turn templates off project-wide with
   `<configuration><stringTemplates>false</stringTemplates></configuration>`. `"$5"` and `"$"` are fine.
4. **Write extensions** anywhere in `src/main/java`: a class annotated `@Extension(Target.class)` with
   `public static` methods whose first parameter is `@This Target`. The target can be any class or
   interface, including ones from libraries (e.g. `org.bukkit.entity.Player`) and the JDK.
5. **In IntelliJ**: install the Lotus plugin, reload the Maven project (Maven tool window → *Reload All*),
   and enable *Delegate IDE build/run actions to Maven* (see below). Don't edit `.idea/compiler.xml`
   by hand; the delegate setting lives in the Settings dialog.

Gradle builds aren't supported yet; only the Maven plugin exists.

## Shipping extensions in your own API

An API or library you write can ship extensions to every project that depends on it. Lotus finds
`@Extension` classes inside dependency jars, both in the Maven plugin and in IntelliJ.

**In the API project:**

```xml
<dependency>
    <groupId>dev.lotus</groupId>
    <artifactId>lotus-annotations</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

```java
@Extension(Wallet.class)
public final class WalletExtensions {
    public static boolean canAfford(@This Wallet wallet, long price) { ... }
    public static Wallet empty() { ... }
}
```

Add `lotus-maven-plugin` to the API too only if the API's own code *calls* extensions or uses templates.
Then `mvn install` (or deploy) the API as usual.

**In a project that uses the API:** depend on it, and add `lotus-annotations` and `lotus-maven-plugin` as
shown above. `wallet.canAfford(100)` and `Wallet.empty()` then just work, and IntelliJ treats them as real
methods after a Maven reload. Re-run `mvn install` on the API whenever its extensions change.

Notes:
- Extension classes must be `public`, and their methods `public static`, to be usable from other projects.
- Projects that don't use Lotus can still use your API by calling the static methods directly:
  `WalletExtensions.canAfford(wallet, 100)`. Nothing in the API's jar depends on Lotus at runtime.
- Publish a sources jar (`maven-source-plugin`) so Ctrl+Click on an extension shows your real code instead
  of decompiled classes.

## Using it in a Maven project

```xml
<dependencies>
    <dependency>
        <groupId>dev.lotus</groupId>
        <artifactId>lotus-annotations</artifactId>
        <version>1.0.0</version>
        <scope>provided</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>dev.lotus</groupId>
            <artifactId>lotus-maven-plugin</artifactId>
            <version>1.0.0</version>
            <executions>
                <execution>
                    <goals>
                        <goal>transform</goal>
                        <goal>transform-test</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

Options (`<configuration>` or `-D`): `lotus.stringTemplates` (default `true`), `lotus.extensions` (default `true`),
`lotus.languageLevel` (e.g. `21`; default: newest JavaParser supports), `lotus.skip`.

The plugin writes lowered sources to `target/lotus/main` (and `target/lotus/test`) and points the compiler at
them. Formatting and line numbers are preserved, so stack traces and the debugger still line up with your
original files.

**In IntelliJ**, enable *Settings → Build, Execution, Deployment → Build Tools → Maven → Runner →
Delegate IDE build/run actions to Maven*, so that IDE builds go through the Lotus Maven plugin.
Without it, IntelliJ's built-in compiler fails with `cannot find symbol` for every extension call. The Lotus
IntelliJ plugin detects this, stops the build with an explanation and offers a one-click
**Delegate build to Maven** button.

## Building

Maven modules (building Lotus itself needs JDK 17+; what it produces runs on JDK 8+):

```bash
mvn install
```

Run the example:

```bash
java -cp lotus-example/target/classes dev.lotus.example.Main
```

IntelliJ plugin (needs JDK 21+ to run Gradle; a JDK 25 toolchain is provisioned automatically):

```bash
cd lotus-intellij
./gradlew buildPlugin
```

The installable zip lands in `lotus-intellij/build/distributions/`. Install it with *Settings → Plugins →
⚙ → Install Plugin from Disk…*. `./gradlew runIde` starts a sandbox IDE with the plugin loaded,
`./gradlew test` runs the plugin tests, and `./gradlew verifyPlugin` checks API compatibility against the
oldest supported IDE (2024.3) and the current one.

## How it works

`javac` can't be told that `Player` has a `heal` method, so Lotus lowers the syntax before `javac` sees it.
The Maven plugin parses every source file with JavaParser, resolves receiver types with the symbol solver,
and rewrites only the calls that don't resolve to a real member but do match an extension
(`player.heal(5)` → `dev.lotus.example.PlayerExtension.heal(player, 5)`). Templates become string
concatenation (`"Hi $name"` → `("Hi " + name)`). Edits are made with JavaParser's lexical-preserving printer,
so everything else in the file is left byte-for-byte unchanged.

In the IDE, the plugin adds each extension to its target class as a synthetic member, which is what makes
IntelliJ treat it as a real method everywhere.

## Limitations

- IDE builds must be delegated to Maven (IntelliJ's own JPS builder doesn't run the transformer).
- Bound method references to extensions (`player::isAlive`) aren't supported. Use a lambda or `Player::isAlive`.
- Compiler errors from `javac` point at the lowered copy in `target/lotus/…` (same line numbers as your file).
- Extensions are Java-only; Kotlin code in the same project doesn't see them.
