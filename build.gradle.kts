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
