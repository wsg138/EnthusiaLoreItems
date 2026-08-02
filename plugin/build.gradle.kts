plugins {
    id("com.gradleup.shadow")
}

val pluginVersion = project.version.toString()

dependencies {
    implementation(project(":api"))
    implementation(project(":adapters-paper"))
    implementation(project(":adapters-sqlite"))
    implementation("org.snakeyaml:snakeyaml-engine:2.9")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

tasks {
    processResources {
        inputs.property("version", pluginVersion)
        filesMatching("plugin.yml") {
            expand("version" to pluginVersion)
        }
    }
    shadowJar {
        archiveClassifier.set("")
    }
    build {
        dependsOn(shadowJar)
    }
}
