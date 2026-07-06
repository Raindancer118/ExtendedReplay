dependencies {
    implementation(project(":extendedreplay-api"))
    implementation(project(":extendedreplay-core"))
    implementation(project(":extendedreplay-storage"))
    implementation(project(":extendedreplay-transport"))

    compileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}")
    compileOnly("org.xerial:sqlite-jdbc:${property("sqliteJdbcVersion")}")
    compileOnly("com.github.luben:zstd-jni:${property("zstdJniVersion")}")
    compileOnly("org.java-websocket:Java-WebSocket:${property("javaWebsocketVersion")}")
}

val props = mapOf(
    "version" to project.version.toString(),
    "sqliteJdbcVersion" to project.property("sqliteJdbcVersion").toString(),
    "zstdJniVersion" to project.property("zstdJniVersion").toString(),
    "javaWebsocketVersion" to project.property("javaWebsocketVersion").toString(),
)

tasks.processResources {
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

// Plugin jar bundles only our own modules; third-party libraries are resolved at
// runtime by Paper's plugin library loader (see "libraries" in plugin.yml).
tasks.jar {
    archiveBaseName.set("ExtendedReplay")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    listOf(":extendedreplay-api", ":extendedreplay-core",
        ":extendedreplay-storage", ":extendedreplay-transport").forEach { module ->
        val sourceSets = project(module).extensions
            .getByType(SourceSetContainer::class.java)
        from(sourceSets.named("main").get().output)
        dependsOn("$module:classes")
    }
}
