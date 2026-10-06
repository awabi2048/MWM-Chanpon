import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.20"
    id("com.gradleup.shadow") version "9.6.1"
    `maven-publish`
}

group = "me.awabi2048"
version = "26.1006.1"

repositories {
    mavenLocal()
    maven { url = uri("../.m2-paper26-kotlin2320") }
    maven("https://jitpack.io")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/maven-snapshots/")
    maven("https://maven.enginehub.org/repo/")
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

// pom.xml の provided スコープ相当（compileOnly へ配置し、テストのコンパイル・実行双方で見えるよう testImplementation にも追加）
val providedDeps = listOf(
    "io.papermc.paper:paper-api:26.2.build.129-stable",
    "org.jetbrains.kotlin:kotlin-stdlib:2.3.20",
    "com.awabi2048:CC-System:26.1006.1",
    "me.awabi2048:chanpon-utilities:26.905.2",
    "awabi2048:my-world-manager:26.905.2",
    "net.luckperms:api:5.4",
    "com.sk89q.worldedit:worldedit-bukkit:7.3.16",
    "com.sk89q.worldguard:worldguard-bukkit:7.0.14",
    "org.geysermc.floodgate:api:2.2.5-SNAPSHOT",
    "org.geysermc.cumulus:cumulus:2.0.0-SNAPSHOT",
)

dependencies {
    implementation("com.github.Querz:NBT:6.1")
    providedDeps.forEach {
        compileOnly(it)
        testImplementation(it)
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

// pom.xml の shade artifactSet 除外相当（kotlin-stdlib と annotations は同梱しない）
configurations.named("runtimeClasspath") {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}

tasks.test {
    useJUnitPlatform()
}

// pom.xml の resources filtering 相当（${project.version} を展開）
tasks.processResources {
    val versionString = project.version.toString()
    inputs.property("projectVersion", versionString)
    filesMatching("plugin.yml") {
        expand(mapOf("project" to mapOf("version" to versionString)))
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
}

publishing {
    publications {
        create<MavenPublication>("plugin") {
            artifactId = "MWM-Chanpon"
            from(components["shadow"])
        }
    }
    repositories {
        maven {
            name = "workspace"
            url = uri("../.m2-paper26-kotlin2320")
        }
    }
}
