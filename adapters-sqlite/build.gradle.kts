val sqliteJdbcVersion = providers.gradleProperty("sqliteJdbcVersion").get()

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")
}
