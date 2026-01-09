import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.0"
    id("fabric-loom") version "1.14.1"
    id("maven-publish")
}

val modVersionValue = providers.gradleProperty("mod_version").get()
val minecraftVersionValue = providers.gradleProperty("minecraft_version").get()
val loaderVersionValue = providers.gradleProperty("loader_version").get()
val kotlinLoaderVersionValue = providers.gradleProperty("kotlin_loader_version").get()
val yarnMappingsValue = providers.gradleProperty("yarn_mappings").get()
val fabricVersionValue = providers.gradleProperty("fabric_version").get()
val archivesBaseNameValue = providers.gradleProperty("archives_base_name").get()
val mavenGroupValue = providers.gradleProperty("maven_group").get()

version = modVersionValue
group = mavenGroupValue

base {
    archivesName.set(archivesBaseNameValue)
}

val targetJavaVersion = 21
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    // Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
    // if it is present.
    // If you remove this line, sources will not be generated.
    withSourcesJar()
}

loom {
    mods {
        register("memento") {
            sourceSet("main")
        }
    }
}


repositories {
    // Add repositories to retrieve artifacts from in here.
    // You should only use this when depending on other mods because
    // Loom adds the essential maven repositories to download Minecraft and libraries from automatically.
    // See https://docs.gradle.org/current/userguide/declaring_repositories.html
    // for more information about repositories.
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersionValue")
    mappings("net.fabricmc:yarn:$yarnMappingsValue:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersionValue")
    modImplementation("net.fabricmc:fabric-language-kotlin:$kotlinLoaderVersionValue")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricVersionValue")
}

tasks.processResources {
    // This task is not configuration-cache compatible due to expand()
    notCompatibleWithConfigurationCache(
        "Uses Kotlin DSL expand() which captures script instance"
    )

    inputs.property("version", modVersionValue)
    inputs.property("minecraft_version", minecraftVersionValue)
    inputs.property("loader_version", loaderVersionValue)
    inputs.property("kotlin_loader_version", kotlinLoaderVersionValue)

    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(
            "version" to modVersionValue,
            "minecraft_version" to minecraftVersionValue,
            "loader_version" to loaderVersionValue,
            "kotlin_loader_version" to kotlinLoaderVersionValue
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    // ensure that the encoding is set to UTF-8, no matter what the system default is
    // this fixes some edge cases with special characters not displaying correctly
    // see http://yodaconditions.net/blog/fix-for-java-file-encoding-problems-with-gradle.html
    // If Javadoc is generated, this must be specified in that task too.
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}

// configure the maven publication
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = project.property("archives_base_name") as String
            from(components["java"])
        }
    }

    // See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
    repositories {
        // Add repositories to publish to here.
        // Notice: This block does NOT have the same function as the block in the top level.
        // The repositories here will be used for publishing your artifact, not for
        // retrieving dependencies.
    }
}
