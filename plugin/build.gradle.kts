plugins {
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":api"))
    implementation(project(":adapters-paper"))
    implementation(project(":adapters-sqlite"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

tasks {
    processResources {
        val expansion = mapOf("version" to project.version.toString())
        inputs.properties(expansion)
        expand(expansion)
    }
    shadowJar {
        archiveClassifier.set("")
    }
    build {
        dependsOn(shadowJar)
    }
}
