import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
        plugin(providers.gradleProperty("lsp4ijVersion").map { "com.redhat.devtools.lsp4ij:$it" })
        bundledPlugin("org.jetbrains.plugins.textmate")
    }

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    // The server tests build and read JSON-RPC messages.
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

/**
 * Compiles the Teal server (ADR 0002) with `cyan build`, after checking that
 * `tl` is the pinned version: type checking only covers the `tl` it ran against.
 */
abstract class CompileTeal @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Input
    abstract val tlVersion: Property<String>

    /** cyan options placed before `build`, overriding tlconfig.lua. */
    @get:Input
    abstract val cyanOptions: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** The resource root: cyan's build dir (tlconfig.lua or `-b`) minus its `teal-server` leaf. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val projectDir: DirectoryProperty

    /**
     * For the server itself: also write `tl_pin.lua` (the pinned tl version,
     * which the server checks at startup) and `files.txt` (every server
     * script, which TealServerScripts extracts) next to cyan's output.
     */
    @get:Input
    abstract val serverFiles: Property<Boolean>

    init {
        serverFiles.convention(false)
    }

    @TaskAction
    fun compile() {
        val found = run("lua", "-e", "io.write(require('tl').version())", hint = "install Lua and `luarocks install tl ${tlVersion.get()}`")
        if (found != tlVersion.get()) {
            throw GradleException("tl $found found, but the build is pinned to tl ${tlVersion.get()} (tlVersion in gradle.properties): `luarocks install tl ${tlVersion.get()}`")
        }
        // --update-all: Gradle already decided the sources changed, and cyan's own
        // mtime check misses edits made within the second of the last build.
        run("cyan", *cyanOptions.get().toTypedArray(), "build", "--update-all", "--prune", hint = "`luarocks install cyan`")

        if (serverFiles.get()) {
            val dir = outputDir.get().dir("teal-server").asFile
            dir.resolve("tl_pin.lua").writeText("return \"${tlVersion.get()}\"\n")
            val scripts = dir.listFiles { f -> f.name.endsWith(".lua") }!!.map { it.name }.sorted()
            dir.resolve("files.txt").writeText(scripts.joinToString("\n", postfix = "\n"))
        }
    }

    private fun run(vararg command: String, hint: String): String {
        val out = ByteArrayOutputStream()
        val result = try {
            exec.exec {
                commandLine(*command)
                workingDir = projectDir.get().asFile
                standardOutput = out
                errorOutput = out
                isIgnoreExitValue = true
            }
        } catch (e: Exception) {
            throw GradleException("cannot run `${command.first()}`: $hint", e)
        }
        if (result.exitValue != 0) {
            throw GradleException("`${command.joinToString(" ")}` failed:\n$out")
        }
        return out.toString().trim()
    }
}

val compileTeal = tasks.register<CompileTeal>("compileTeal") {
    tlVersion = providers.gradleProperty("tlVersion")
    sources.from("tlconfig.lua", "src/main/teal")
    outputDir = layout.buildDirectory.dir("generated/teal")
    projectDir = layout.projectDirectory
    serverFiles = true
}

// The Lua unit tests, compiled against the server sources.
val compileTealTest = tasks.register<CompileTeal>("compileTealTest") {
    tlVersion = providers.gradleProperty("tlVersion")
    cyanOptions = listOf("-s", "src/test/teal", "-b", "build/generated/teal-test/teal-server", "-I", "src/main/teal")
    sources.from("tlconfig.lua", "src/test/teal", "src/main/teal")
    outputDir = layout.buildDirectory.dir("generated/teal-test")
    projectDir = layout.projectDirectory
}

sourceSets.main {
    resources.srcDir(compileTeal)
}

sourceSets.test {
    resources.srcDir(compileTealTest)
}

intellijPlatform {
    pluginConfiguration {
        name = "Teal"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
        changeNotes = """
            <ul>
                <li>0.7.0: The language server is now written in Teal and type-checked against the <code>tl</code> library it runs on. It needs <code>tl</code> 0.24.8 or newer (<code>luarocks install tl</code>), and says so at startup instead of failing later. Lex errors, such as a stray character or an unterminated string, now show <code>tl</code>'s own message instead of "unexpected token 'nil'".</li>
                <li>0.6.0: Richer highlighting. Declared function names, parameters and types get their own colors as you type, and the language server now tells locals, parameters, globals, <code>&lt;const&gt;</code> locals, the standard library and fields apart. Colors come from your scheme's standard Language Defaults, like Lua files.</li>
                <li>0.5.1: Fixed the Teal file icon.</li>
                <li>0.5.0: Teal support now comes from a language server bundled with the plugin, built on the <code>tl</code> compiler. It needs Lua and <code>luarocks install tl</code>; teal-language-server is no longer needed. Adds completion, signature help and type definition, better hover and go-to-definition, Find Usages on record fields, and spell-checking limited to comments and strings.</li>
                <li>0.4.0: Cmd/Ctrl+Click on a <code>require("...")</code> string opens the required module's file.</li>
                <li>0.3.0: Quick fix for <code>unknown type X</code> errors: Alt+Enter adds the <code>require</code> of the module that declares the global type.</li>
                <li>0.2.0: Find Usages for .tl files (current document), implemented in the plugin itself since teal-language-server doesn't support it.</li>
                <li>0.1.0: Initial release &mdash; syntax highlighting, inline diagnostics, hover, and go-to-definition for .tl files, plus a distinct file icon in the project tree.</li>
            </ul>
        """.trimIndent()
    }
}

kotlin {
    jvmToolchain(17)
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
}
