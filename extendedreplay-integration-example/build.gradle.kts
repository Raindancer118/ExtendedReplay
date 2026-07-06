// Compile-only example showing how a minigame plugin (e.g. TheHungerGames) integrates
// with ExtendedReplay. Depends ONLY on the public API — never the other way around.
dependencies {
    compileOnly(project(":extendedreplay-api"))
    compileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}")
}
