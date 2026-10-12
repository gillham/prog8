plugins {
    kotlin("jvm")
}


dependencies {
    implementation(project(":codeCore"))
    implementation(project(":simpleAst"))
    implementation(project(":intermediate"))

    testImplementation(testFixtures(project(":codeCore")))
    testImplementation("io.kotest:kotest-runner-junit5")
}
