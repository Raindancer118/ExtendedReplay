dependencies {
    api(project(":extendedreplay-core"))
    // Provided at runtime through Paper's plugin library loader (plugin.yml "libraries").
    compileOnly("org.java-websocket:Java-WebSocket:${property("javaWebsocketVersion")}")
    compileOnly("com.github.luben:zstd-jni:${property("zstdJniVersion")}")
    compileOnly("org.slf4j:slf4j-api:2.0.16")

    testImplementation("org.java-websocket:Java-WebSocket:${property("javaWebsocketVersion")}")
    testImplementation("com.github.luben:zstd-jni:${property("zstdJniVersion")}")
    testImplementation("org.slf4j:slf4j-api:2.0.16")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}
