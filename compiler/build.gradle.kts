plugins {
    id("application")
    kotlin("jvm")
    id("com.gradleup.shadow") version "9.6.1"
    id("com.peterabeles.gversion") version "1.11.0"
}

// suffix and release counter kept out of gradle.properties so upstream version bumps merge cleanly
val llvmRelease = rootProject.file("llvm-release.properties").readLines()
    .single { it.startsWith("llvmRelease=") }.substringAfter('=').trim()
version = "$version-llvm.$llvmRelease"

dependencies {
    implementation(project(":codeCore"))
    implementation(project(":simpleAst"))
    implementation(project(":codeOptimizers"))
    implementation(project(":compilerAst"))
    implementation(project(":codeGenCpu6502"))
    implementation(project(":codeGenNew6502"))
    implementation(project(":codeGenM68k"))
    implementation(project(":codeGenLlvm"))
    implementation(project(":codeGenIntermediate"))
    implementation(project(":intermediate"))
    implementation(project(":virtualmachine"))
    implementation("com.github.ajalt.clikt:clikt:5.1.0") {
        exclude(group = "com.github.ajalt.mordant", module = "mordant")
    }
    implementation("com.github.ajalt.mordant:mordant-core:3.1.0")
    implementation("com.michael-bull.kotlin-result:kotlin-result-jvm:2.3.1")

    testImplementation(project(":codeCore"))
    testImplementation(testFixtures(project(":codeCore")))
    testImplementation(project(":intermediate"))
    testImplementation(project(":codeGenLlvm"))
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("org.codeberg.irmen:ksim65:v2.2")
}

// Exclude transitive antlr4 dependency (we only need it in parser module)
configurations.all {
    exclude(group = "org.antlr", module = "antlr4")
}

// Post-process the generated launcher scripts: pass --enable-native-access only to
// JVMs that understand it (JDK 18+), to avoid JNA restricted-method warnings on newer JDKs.
fun patchLaunchScripts(scriptDir: File) {
    val unixScript = scriptDir.resolve("prog8c")
    if (unixScript.canRead()) {
        val marker = "eval \"set -- $("
        val text = unixScript.readText()
        check(marker in text) { "unexpected POSIX launch script layout for prog8c" }
        val probe = $$"""if "$JAVACMD" --enable-native-access=ALL-UNNAMED -version >/dev/null 2>&1; then
    JAVA_OPTS="$JAVA_OPTS --enable-native-access=ALL-UNNAMED"
fi

"""
        unixScript.writeText(text.replace(marker, probe + marker))
    }
    val windowsScript = scriptDir.resolve("prog8c.bat")
    if (windowsScript.canRead()) {
        val marker = "@rem Execute prog8c"
        val text = windowsScript.readText()
        check(marker in text) { "unexpected Windows launch script layout for prog8c" }
        val probe = """@rem Prog8: add --enable-native-access on JVMs that support it (JDK 18+) to avoid JNA restricted-method warnings
"%JAVA_EXE%" --enable-native-access=ALL-UNNAMED -version >NUL 2>&1
if not errorlevel 1 set JAVA_OPTS=%JAVA_OPTS% --enable-native-access=ALL-UNNAMED

"""
        windowsScript.writeText(text.replace(marker, probe + marker))
    }
}

tasks.startScripts {
    enabled = true
}

tasks.withType<org.gradle.jvm.application.tasks.CreateStartScripts>().configureEach {
    doLast {
        patchLaunchScripts(outputDir!!)
    }
}

application {
    mainClass.set("prog8.CompilerMainKt")
    applicationName = "prog8c"
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "prog8.CompilerMainKt")
        attributes("Enable-Native-Access" to "ALL-UNNAMED")
    }
}

tasks.shadowJar {
    archiveBaseName.set("prog8c")
    archiveVersion.set(version.toString())
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    manifest {
        attributes("Main-Class" to "prog8.CompilerMainKt")
        attributes("Enable-Native-Access" to "ALL-UNNAMED")
    }
    filesMatching("META-INF/LICENSE") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    // minimize()
}

gversion {
    srcDir = "src/" // path is relative to the sub-project by default
    classPackage = "prog8.buildversion"
    className = "Version"
    language = "kotlin"
    debug = false
    annotate = ""
}

tasks.build {
    finalizedBy(tasks.installDist, tasks.installShadowDist)
}

tasks.compileKotlin {
    dependsOn(tasks.createVersionFile) // , failDirtyNotSnapshot
}

tasks.compileJava {
    dependsOn(tasks.createVersionFile)
}

// Copies the shadow distribution to ~/.local (or the directory of -PlocalPrefix=...) as prog8c-llvm, so that compiler
// stays usable while the build rebuilds. "gradle build installLocal" only installs a compiler whose tests passed.
val installLocal by tasks.registering {
    dependsOn(tasks.installShadowDist)
    mustRunAfter(tasks.build, tasks.test)
    doLast {
        val prefix = File((findProperty("localPrefix") as String?) ?: "${System.getProperty("user.home")}/.local")
        val dist = layout.buildDirectory.dir("install/prog8c-shadow").get().asFile
        val lib = File(prefix, "lib").apply { mkdirs() }
        val bin = File(prefix, "bin").apply { mkdirs() }
        // the new copy goes in under another name first and replaces the old one by renames, so a running compiler keeps working
        val installed = File(lib, "prog8c-llvm")
        val staging = File(lib, "prog8c-llvm.new").apply { deleteRecursively() }
        val previous = File(lib, "prog8c-llvm.old").apply { deleteRecursively() }
        dist.copyRecursively(staging)
        staging.resolve("bin").listFiles()!!.forEach { it.setExecutable(true) }
        if (installed.exists())
            check(installed.renameTo(previous)) { "can't move $installed aside" }
        check(staging.renameTo(installed)) { "can't rename $staging to $installed" }
        previous.deleteRecursively()
        // a versioned copy of the jar, like those of the released versions
        val jar = installed.resolve("lib").listFiles()!!.single { it.name.endsWith("-all.jar") }
        val versioned = File(lib, jar.name)
        val versionedStaging = File(lib, jar.name + ".new")
        jar.copyTo(versionedStaging, overwrite = true)
        check(versionedStaging.renameTo(versioned) || (versioned.delete() && versionedStaging.renameTo(versioned)))
        val windows = System.getProperty("os.name").startsWith("Windows")
        val launcher = File(bin, if (windows) "prog8c-llvm.bat" else "prog8c-llvm")
        if (windows)
            launcher.writeText("@\"${installed.resolve("bin/prog8c.bat")}\" %*\r\n")
        else {
            launcher.writeText($$"#!/bin/sh\nexec \"$${installed.resolve("bin/prog8c")}\" \"$@\"\n")
            launcher.setExecutable(true)
        }
        println("installed $launcher, running ${installed.resolve("bin")}, and ${versioned.name} in $lib")
    }
}
