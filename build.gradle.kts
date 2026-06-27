plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
    signing
}

group = "io.github.agent0876"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.lz4:lz4-java:1.8.0")
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            
            pom {
                name.set("anvil")
                description.set("Kotlin Anvil (MCA) file format handler for Minecraft Java Edition")
                url.set("https://github.com/agent0876/anvil")
                
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                
                developers {
                    developer {
                        id.set("agent0876")
                        name.set("agent0876")
                    }
                }
                
                scm {
                    connection.set("scm:git:git://github.com/agent0876/anvil.git")
                    developerConnection.set("scm:git:ssh://github.com/agent0876/anvil.git")
                    url.set("https://github.com/agent0876/anvil")
                }
            }
        }
    }
    
    repositories {
        maven {
            name = "OSSRH"
            val releasesRepoUrl = uri("https://s01.oss.sonatype.org/content/repositories/releases/")
            val snapshotsRepoUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
            credentials {
                username = project.findProperty("ossrhUsername") as String?
                password = project.findProperty("ossrhPassword") as String?
            }
        }
    }
}

signing {
    val keyId = project.findProperty("signing.keyId") as String?
    val password = project.findProperty("signing.password") as String?
    val secretKey = project.findProperty("signing.secretKey") as String?
    if (!keyId.isNullOrEmpty() && !password.isNullOrEmpty() && !secretKey.isNullOrEmpty()) {
        useInMemoryPgpKeys(keyId, secretKey, password)
        sign(publishing.publications["mavenJava"])
    }
}
