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

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "sqliteJdbcVersion" to property("sqliteJdbcVersion").toString(),
        "zstdJniVersion" to property("zstdJniVersion").toString(),
        "javaWebsocketVersion" to property("javaWebsocketVersion").toString(),
    )
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
    val ownModules = configurations.runtimeClasspath.get()
        .filter { it.name.startsWith("extendedreplay-") }
    from(ownModules.map { zipTree(it) })
}
