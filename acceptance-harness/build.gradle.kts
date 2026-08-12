val paperApiVersion: String by project
val paperApiDependency = "io.papermc.paper:paper-api:$paperApiVersion"

dependencies {
    compileOnly(project(":api"))
    compileOnly(paperApiDependency)
}
