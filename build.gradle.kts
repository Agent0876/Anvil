plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

group   = "io.github.moltenmc"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.lz4)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(25)
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(
        groupId    = "io.github.moltenmc",
        artifactId = "anvil",
        version    = "1.0.0"
    )

    pom {
        name.set("anvil")
        description.set("Kotlin Anvil (MCA) file format handler for Minecraft Java Edition")
        url.set("https://github.com/MoltenMC/Anvil")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("agent0876")
                name.set("Agent0876")
                email.set("shinseungmin070920@gmail.com")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/MoltenMC/Anvil.git")
            developerConnection.set("scm:git:ssh://github.com/MoltenMC/Anvil.git")
            url.set("https://github.com/MoltenMC/Anvil")
        }
    }
}
