plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "io.github.doggylover314"
version = "1.0.0"

val paperVersion = "26.2.build.129-stable"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperVersion")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 25
        options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing"))
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("paper-plugin.yml") { expand(props) }
    }

    shadowJar {
        archiveClassifier = ""
    }

    jar {
        // The shadow jar is the deliverable; keep the plain jar out of the way.
        archiveClassifier = "plain"
    }

    assemble {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
    }
}
