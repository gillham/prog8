plugins {
    kotlin("jvm")
}


dependencies {
    implementation(project(":codeCore"))
    implementation(project(":intermediate"))
    // implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("com.michael-bull.kotlin-result:kotlin-result-jvm:2.1.0")
    testImplementation("io.kotest:kotest-runner-junit5-jvm:5.9.1")
    testImplementation("io.kotest:kotest-framework-datatest:5.9.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    main {
        java {
            setSrcDirs(listOf("${project.projectDir}/src"))
        }
        resources {
            setSrcDirs(listOf("${project.projectDir}/res"))
        }
    }

    test {
        java {
            setSrcDirs(listOf("${project.projectDir}/test"))
        }
    }
}

tasks.test {
    // Enable JUnit 5 (Gradle 4.6+).
    useJUnitPlatform()

    // Show test results.
    testLogging {
        events("skipped", "failed")
    }
}
