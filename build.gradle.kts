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
