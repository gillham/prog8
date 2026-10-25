package prog8tests.helpers

import prog8.code.target.LlvmTarget
import prog8.code.target.LlvmTripleProfile


/** The llvm64 triple profile of the machine the tests run on, which the native tests build their programs with; null when the compiler has none for it. */
val hostLlvmProfile: LlvmTripleProfile? =
    LlvmTarget.hostTriple64(System.getProperty("os.name"), System.getProperty("os.arch"))?.let { LlvmTripleProfile.resolve(it) }

/** The llvm64 target of the machine the tests run on, or the default llvm64 target where there is no profile for it (the native tests don't run there). */
val hostLlvmTarget: LlvmTarget
    get() = hostLlvmProfile?.let { LlvmTarget(8, it) } ?: LlvmTarget()

/** Why the native tests don't run on this machine, or null when they can. */
val noHostLlvmProfileReason: String? =
    if (hostLlvmProfile == null) "the compiler has no llvm64 triple profile for this host (${System.getProperty("os.name")} ${System.getProperty("os.arch")})" else null
