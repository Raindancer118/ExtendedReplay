plugins {
    java
}

allprojects {
    group = "dev.raindancer118.extendedreplay"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    java {
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = 21
        options.encoding = "UTF-8"
    }

    dependencies {
        "compileOnly"("org.jetbrains:annotations:26.0.2")
        "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        "testImplementation"("org.assertj:assertj-core:3.27.3")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            showExceptions = true
            showCauses = true
        }
    }
}
