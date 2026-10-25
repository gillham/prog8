package prog8tests.helpers

import prog8.code.target.LlvmTarget
import prog8.code.target.LlvmTripleProfile


/** The built-in llvm64 triple profile for a host with these Java `os.name` and `os.arch` properties, or null when there is none. */
fun hostProfileName(osName: String, osArch: String): String? {
    val arch = when (osArch) {
        "aarch64", "arm64" -> "aarch64"
        "amd64", "x86_64" -> "x86_64"
        "riscv64" -> "riscv64"
        else -> return null
    }
    return when {
        osName.startsWith("Mac") -> if (arch == "aarch64") "arm64-apple-macosx" else if (arch == "x86_64") "x86_64-apple-macosx" else null
        osName.startsWith("Linux") -> "$arch-unknown-linux-gnu"
        else -> null
    }
}

/** The llvm64 triple profile of the machine the tests run on, which the native tests build their programs with; null when the compiler has none for it. */
val hostLlvmProfile: LlvmTripleProfile? =
    hostProfileName(System.getProperty("os.name"), System.getProperty("os.arch"))?.let { LlvmTripleProfile.resolve(it) }

/** The llvm64 target of the machine the tests run on, or the default llvm64 target where there is no profile for it (the native tests don't run there). */
val hostLlvmTarget: LlvmTarget
    get() = hostLlvmProfile?.let { LlvmTarget(8, it) } ?: LlvmTarget()

/** Why the native tests don't run on this machine, or null when they can. */
val noHostLlvmProfileReason: String? =
    if (hostLlvmProfile == null) "the compiler has no llvm64 triple profile for this host (${System.getProperty("os.name")} ${System.getProperty("os.arch")})" else null
