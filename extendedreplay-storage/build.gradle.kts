dependencies {
    api(project(":extendedreplay-core"))
    // Provided at runtime through Paper's plugin library loader (plugin.yml "libraries").
    compileOnly("org.xerial:sqlite-jdbc:${property("sqliteJdbcVersion")}")
    compileOnly("com.github.luben:zstd-jni:${property("zstdJniVersion")}")

    testImplementation("org.xerial:sqlite-jdbc:${property("sqliteJdbcVersion")}")
    testImplementation("com.github.luben:zstd-jni:${property("zstdJniVersion")}")
}
