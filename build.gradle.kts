import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.internal.config.LanguageFeature


plugins {
    kotlin("jvm") version "2.2.0"
}

allprojects {
    apply(plugin="kotlin")

    repositories {
        mavenLocal()
        mavenCentral()
    }

    kotlin {
        compilerOptions {
            freeCompilerArgs = listOf("-Xwhen-guards")
            jvmTarget = JvmTarget.JVM_11
            jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
        }
        sourceSets.all {
            languageSettings {
                // enable language features like so:
                // enableLanguageFeature(LanguageFeature.WhenGuards.name)
            }
        }
    }

    java {
        targetCompatibility = JavaVersion.VERSION_11
        sourceCompatibility = JavaVersion.VERSION_11
    }
}
