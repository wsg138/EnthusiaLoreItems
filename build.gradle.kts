import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import java.util.Properties

plugins {
    base
    jacoco
    id("com.gradleup.shadow") version "8.3.6" apply false
    id("com.github.spotbugs") version "6.0.10" apply false
}

group = "net.enthusia.loreitems"

val sourceProperties = Properties().apply {
    rootDir.resolve("gradle.properties").inputStream().use(::load)
}
val sourceReleaseVersion = sourceProperties.getProperty("releaseVersion")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: error("gradle.properties must define releaseVersion")
val requestedReleaseVersion = providers.gradleProperty("releaseVersion").orNull
if (requestedReleaseVersion != null && requestedReleaseVersion != sourceReleaseVersion) {
    logger.lifecycle(
        "Ignoring -PreleaseVersion=$requestedReleaseVersion; source-controlled releaseVersion=$sourceReleaseVersion is authoritative."
    )
}
version = sourceReleaseVersion

allprojects {
    group = rootProject.group
    version = rootProject.version

    tasks.withType<org.gradle.api.tasks.bundling.AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")
    apply(plugin = "com.github.spotbugs")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        withSourcesJar()
    }

    extensions.configure<SpotBugsExtension> {
        ignoreFailures = true
        showProgress = true
        effort = Effort.MAX
        reportLevel = Confidence.LOW
        toolVersion = "4.8.4"
    }

    tasks.withType<SpotBugsTask>().configureEach {
        if (name == "spotbugsTest") {
            enabled = false
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.13.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}
