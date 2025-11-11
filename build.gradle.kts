plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
    id("co.uzzu.dotenv.gradle") version "4.0.0"
}

group = "com.aiekick"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        clion("2024.1")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"  // CLion 2024.1 minimum
            untilBuild = provider { null }  // Pas de limite supérieure
        }
        changeNotes = """
            First release.
        """.trimIndent()
    }
    pluginVerification {
        ides {
            create("CL", "2024.1")
            create("CL", "2025.2.4")
        }
    }
    signing {
        certificateChainFile.set(file("keys/chain.crt"))
        privateKeyFile.set(file("keys/private.pem"))
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token.set(file("keys/token").toString())
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
