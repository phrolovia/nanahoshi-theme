import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(21)
}

// Configure project's dependencies
repositories {
    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/platforms.html#sub:version-catalog
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))

        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

        testFramework(TestFrameworkType.Platform)
    }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels = providers.gradleProperty("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

// Configure Gradle Kover Plugin - read more: https://github.com/Kotlin/kotlinx-kover#configuration
kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    register("cleanProperties") {
        description = "Remove properties starting with specific words from JSON theme files"
        group = "build"
        
        val themeDir = layout.projectDirectory.dir("src/main/resources/themes")
        val propertyPrefixes = arrayOf("doki") // Add more prefixes as needed
        
        doLast {
            if (!themeDir.asFile.exists()) {
                println("Theme directory not found: ${themeDir.asFile.absolutePath}")
                return@doLast
            }
            
            var filesProcessed = 0
            var totalRemovedProperties = 0
            
            themeDir.asFileTree.matching {
                include("**/*.json")
            }.forEach { jsonFile ->
                val content = jsonFile.readText()
                val lines = content.lines().toMutableList()
                var removedInFile = 0
                
                // Remove lines that contain properties starting with any prefix
                val iterator = lines.iterator()
                var lineIndex = 0
                val linesToRemove = mutableListOf<Int>()
                
                while (iterator.hasNext()) {
                    val line = iterator.next()
                    val trimmedLine = line.trim()
                    
                    // Check if this line contains a property that starts with any of our prefixes
                    val shouldRemove = propertyPrefixes.any { prefix ->
                        trimmedLine.matches(""""${prefix}[^"]*"\s*:.*""".toRegex(RegexOption.IGNORE_CASE))
                    }
                    
                    if (shouldRemove) {
                        linesToRemove.add(lineIndex)
                        removedInFile++
                    }
                    lineIndex++
                }
                
                // Remove lines in reverse order to preserve indices
                linesToRemove.sortedDescending().forEach { index ->
                    lines.removeAt(index)
                }
                
                if (removedInFile > 0) {
                    // Fix comma issues: ensure proper JSON structure
                    val modifiedContent = lines.joinToString("\n")
                        .replace(""",(\s*[}\]])""".toRegex(), "$1") // Remove trailing commas before closing braces
                        .replace("""(\w+|"[^"]*")(\s*\n\s*)(\w+|"[^"]*")""".toRegex(), "$1,$2$3") // Add missing commas between properties
                        .replace(""",(\s*,)""".toRegex(), ",") // Remove duplicate commas
                        .replace("""\n\s*\n\s*\n""".toRegex(), "\n\n") // Clean up multiple newlines
                    
                    jsonFile.writeText(modifiedContent)
                    totalRemovedProperties += removedInFile
                }
                
                filesProcessed++
            }
            
            println("Processed $filesProcessed JSON files, removed $totalRemovedProperties properties matching prefixes: ${propertyPrefixes.joinToString(", ")}")
        }
    }

    register("updateSchemeNames") {
        description = "Update XML scheme names from corresponding JSON theme files"
        group = "build"
        
        val themeDir = layout.projectDirectory.dir("src/main/resources/themes")
        
        doLast {
            if (!themeDir.asFile.exists()) {
                println("Theme directory not found: ${themeDir.asFile.absolutePath}")
                return@doLast
            }
            
            var filesProcessed = 0
            var filesUpdated = 0
            
            themeDir.asFileTree.matching {
                include("**/*.theme.json")
            }.forEach { jsonFile ->
                val xmlFileName = jsonFile.name.replace(".theme.json", ".xml")
                val xmlFile = File(jsonFile.parentFile, xmlFileName)
                
                if (xmlFile.exists()) {
                    try {
                        // Read theme name from JSON
                        val jsonContent = jsonFile.readText()
                        val nameRegex = """"name"\s*:\s*"([^"]+)"""".toRegex()
                        val nameMatch = nameRegex.find(jsonContent)
                        
                        if (nameMatch != null) {
                            val themeName = nameMatch.groupValues[1]
                            
                            // Read XML content and update scheme name
                            val xmlContent = xmlFile.readText()
                            val schemeRegex = """<scheme name="[^"]*"""".toRegex()
                            val newXmlContent = xmlContent.replace(schemeRegex, """<scheme name="$themeName"""")
                            
                            if (xmlContent != newXmlContent) {
                                xmlFile.writeText(newXmlContent)
                                println("Updated scheme name to '$themeName' in ${xmlFile.name}")
                                filesUpdated++
                            }
                        } else {
                            println("Warning: Could not find 'name' property in ${jsonFile.name}")
                        }
                    } catch (e: Exception) {
                        println("Error processing ${jsonFile.name}: ${e.message}")
                    }
                } else {
                    println("Warning: No corresponding XML file found for ${jsonFile.name}")
                }
                filesProcessed++
            }
            
            println("Processed $filesProcessed JSON files, updated $filesUpdated XML scheme names")
        }
    }

    register("updatePluginXml") {
        description = "Update plugin.xml with all theme files from src/main/resources/themes"
        group = "build"
        
        val themeDir = layout.projectDirectory.dir("src/main/resources/themes")
        val pluginXmlFile = layout.projectDirectory.file("src/main/resources/META-INF/plugin.xml").asFile
        
        doLast {
            if (!themeDir.asFile.exists()) {
                println("Theme directory not found: ${themeDir.asFile.absolutePath}")
                return@doLast
            }
            
            if (!pluginXmlFile.exists()) {
                println("Plugin XML file not found: ${pluginXmlFile.absolutePath}")
                return@doLast
            }
            
            // Collect all theme files and generate themeProvider entries
            val themeProviders = mutableListOf<String>()
            
            themeDir.asFileTree.matching {
                include("**/*.theme.json")
            }.forEach { jsonFile ->
                val relativePath = themeDir.asFile.toPath().relativize(jsonFile.toPath()).toString().replace("\\", "/")
                val themeId = jsonFile.nameWithoutExtension.replace(".theme", "")
                
                themeProviders.add("""        <themeProvider id="$themeId" path="/themes/$relativePath"/>""")
            }
            
            // Sort themeProviders by id
            themeProviders.sort()
            
            // Read current plugin.xml content
            val pluginXmlContent = pluginXmlFile.readText()
            
            // Replace the extensions section with updated themeProviders
            val extensionsStartRegex = """(\s*)<extensions defaultExtensionNs="com\.intellij">""".toRegex()
            val extensionsEndRegex = """(\s*)</extensions>""".toRegex()
            
            val startMatch = extensionsStartRegex.find(pluginXmlContent)
            val endMatch = extensionsEndRegex.find(pluginXmlContent)
            
            if (startMatch != null && endMatch != null) {
                val beforeExtensions = pluginXmlContent.substring(0, startMatch.range.last + 1)
                val afterExtensions = pluginXmlContent.substring(endMatch.range.first)
                
                val newExtensionsContent = buildString {
                    appendLine()
                    themeProviders.forEach { provider ->
                        appendLine(provider)
                    }
                    append("    ")
                }
                
                val newPluginXmlContent = beforeExtensions + newExtensionsContent + afterExtensions
                
                if (pluginXmlContent != newPluginXmlContent) {
                    pluginXmlFile.writeText(newPluginXmlContent)
                    println("Updated plugin.xml with ${themeProviders.size} theme providers")
                } else {
                    println("Plugin.xml is already up to date")
                }
            } else {
                println("Error: Could not find extensions section in plugin.xml")
            }
        }
    }

    register("generateVSCodeThemes") {
        description = "Generate VSCode themes from IntelliJ theme files"
        group = "build"
        
        val themeDir = layout.projectDirectory.dir("src/main/resources/themes")
        val outputDir = layout.buildDirectory.dir("vscode-themes")
        
        doLast {
            if (!themeDir.asFile.exists()) {
                println("Theme directory not found: ${themeDir.asFile.absolutePath}")
                return@doLast
            }
            
            val generator = VSCodeThemeGenerator()
            var themesGenerated = 0
            val themeContributions = mutableListOf<Map<String, Any>>()
            
            // Clean output directory
            outputDir.get().asFile.deleteRecursively()
            outputDir.get().asFile.mkdirs()
            
            // Create themes directory
            val themesOutputDir = File(outputDir.get().asFile, "themes")
            themesOutputDir.mkdirs()
            
            themeDir.asFileTree.matching {
                include("**/*.theme.json")
            }.forEach { jsonFile ->
                try {
                    val vscodeTheme = generator.generateVSCodeTheme(jsonFile)
                    val outputFileName = "${jsonFile.nameWithoutExtension.replace(".dark.theme", "").replace(".theme", "")}-color-theme.json"
                    val outputFile = File(themesOutputDir, outputFileName)
                    
                    generator.writeVSCodeTheme(vscodeTheme, outputFile)
                    
                    // Collect theme contribution data for package.json
                    themeContributions.add(mapOf(
                        "label" to vscodeTheme.name,
                        "uiTheme" to "vs-dark",
                        "path" to "./themes/$outputFileName"
                    ))
                    
                    println("Generated VSCode theme: ${vscodeTheme.name} -> $outputFileName")
                    themesGenerated++
                } catch (e: Exception) {
                    println("Error generating VSCode theme from ${jsonFile.name}: ${e.message}")
                }
            }
            
            // Generate package.json
            val packageJson = mapOf(
                "name" to "nanahoshi-themes",
                "displayName" to "Nanahoshi Themes",
                "description" to "Beautiful anime-inspired themes for VSCode, converted from IntelliJ themes",
                "version" to "1.0.0",
                "publisher" to "nanahoshi",
                "engines" to mapOf("vscode" to "^1.60.0"),
                "categories" to listOf("Themes"),
                "contributes" to mapOf(
                    "themes" to themeContributions
                ),
                "repository" to mapOf(
                    "type" to "git",
                    "url" to "https://github.com/nanahoshi/nanahoshi-theme"
                ),
                "keywords" to listOf("theme", "dark", "anime", "genshin", "honkai", "wuthering"),
                "galleryBanner" to mapOf(
                    "color" to "#1A1820",
                    "theme" to "dark"
                )
            )
            
            val packageJsonFile = File(outputDir.get().asFile, "package.json")
            val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()
            packageJsonFile.writeText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(packageJson))
            
            // Generate README.md
            val readmeContent = buildString {
                appendLine("# Nanahoshi Themes for VSCode")
                appendLine()
                appendLine("Beautiful anime-inspired themes for Visual Studio Code, automatically converted from IntelliJ IDEA themes.")
                appendLine()
                appendLine("## Themes Included")
                appendLine()
                themeContributions.forEach { theme ->
                    appendLine("- ${theme["label"]}")
                }
                appendLine()
                appendLine("## Installation")
                appendLine()
                appendLine("1. Copy this entire folder to your VSCode extensions directory")
                appendLine("2. Restart VSCode")
                appendLine("3. Go to File > Preferences > Color Theme")
                appendLine("4. Select one of the Nanahoshi themes")
                appendLine()
                appendLine("## Source")
                appendLine()
                appendLine("These themes are automatically generated from the [Nanahoshi IntelliJ Theme Plugin](https://github.com/nanahoshi/nanahoshi-theme).")
            }
            
            val readmeFile = File(outputDir.get().asFile, "README.md")
            readmeFile.writeText(readmeContent)
            
            println("Generated $themesGenerated VSCode themes in ${outputDir.get().asFile.absolutePath}")
            println("Package structure created with package.json and README.md")
        }
    }

    processResources {
        dependsOn("cleanProperties", "updateSchemeNames", "updatePluginXml", "generateVSCodeThemes")
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }
}

intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=8082",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                    )
                }
            }

            plugins {
                robotServerPlugin()
            }
        }
    }
}
