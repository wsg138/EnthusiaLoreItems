dependencies {
    testImplementation(project(":domain"))
    testImplementation(project(":application"))
    testImplementation(project(":api"))
    testImplementation(project(":adapters-sqlite"))
    testImplementation(project(":adapters-paper"))
    testImplementation(project(":plugin"))
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
}
