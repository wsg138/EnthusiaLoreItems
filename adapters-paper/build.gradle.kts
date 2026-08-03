dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":api"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.110.0") {
        exclude(group = "org.junit.jupiter")
        exclude(group = "org.junit.platform")
    }
}
