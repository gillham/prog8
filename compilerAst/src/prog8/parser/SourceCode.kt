package prog8.parser

import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CharStreams
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable

/**
 * Encapsulates - and ties together - actual source code (=text)
 * and its [origin].
 */
abstract class SourceCode {

    /**
     * To be used *only* by the parser (as input to a TokenStream).
     * DO NOT mess around with!
     */
    internal abstract fun getCharStream(): CharStream

    /**
     * Whether this [SourceCode] instance was created by
     * factory method [fromResources]
     */
    abstract val isFromResources: Boolean

    /**
     * Where this [SourceCode] instance came from.
     * This can be one of the following:
     * * a normal string representation of a [java.nio.file.Path], if it originates from a file (see [fromPath])
     * * `<String@44c56085>` if was created via [of]
     * * `library:/x/y/z.ext` if it is a library file that was loaded from resources (see [fromResources])
     */
    abstract val origin: String


    /**
     * FIXME: hacking together a [SourceCode]'s "path string"
     * This is really just [origin] with any stuff removed that would render it an invalid path name.
     * (Note: a *valid* path name does NOT mean that the denoted file or folder *exists*)
     */
    fun pathString() =
        origin
            .substringAfter("<").substringBeforeLast(">") // or from plain string?

    /**
     * The source code as plain string.
     * *Note: this is meant for testing and debugging, do NOT use in application code!*
     */
    fun asString() = this.getCharStream().toString()

    /**
     * Deliberately does NOT return the actual text.
     * For this - if at all - use [getCharStream].
     */
    final override fun toString() = "${this.javaClass.name}[${this.origin}]"

    // "static" factory methods
    companion object {

        /**
         * filename prefix to designate library files that will be retreived from internal resources rather than disk
         */
        const val libraryFilePrefix = "library:"

        /**
         * Turn a plain String into a [SourceCode] object.
         * [origin] will be something like `<String@44c56085>`.
         */
        fun of(text: String): SourceCode {
            return object : SourceCode() {
                override val isFromResources = false
                override val origin = "<String@${System.identityHashCode(text).toString(16)}>"
                override fun getCharStream(): CharStream {
                    return CharStreams.fromString(text)
                }
            }
        }

        /**
         * Get [SourceCode] from the file represented by the specified Path.
         * This does not actually *access* the file, but it does check
         * whether it
         * * exists
         * * is a regular file (ie: not a directory)
         * * and is actually readable
         *
         * [origin] will be the given path in absolute and normalized form.
         * @throws NoSuchFileException if the file does not exist
         * @throws AccessDeniedException if the given path points to a directory or the file is non-readable for some other reason
         */
        fun fromPath(path: Path): SourceCode {
            val normalized = path.normalize()
            val file = normalized.toFile()
            if (!path.exists())
                throw NoSuchFileException(file)
            if (path.isDirectory())
                throw AccessDeniedException(file, reason = "Not a file but a directory")
            if (!path.isReadable())
                throw AccessDeniedException(file, reason = "Is not readable")
            return object : SourceCode() {
                override val isFromResources = false
                override val origin = normalized.absolutePathString()
                override fun getCharStream(): CharStream {
                    return CharStreams.fromPath(normalized)
                }
            }
        }

        /**
         * [origin]: `<library:/x/y/z.p8>` for a given `pathString` of "x/y/z.p8"
         */
        fun fromResources(pathString: String): SourceCode {
            val path = Path.of(pathString).normalize()
            val sep = "/"
            val normalized = sep + path.toMutableList().joinToString(sep)
            val rscURL = object{}.javaClass.getResource(normalized)
            if (rscURL == null) {
                val rscRoot = object{}.javaClass.getResource("/")
                throw NoSuchFileException(
                    File(normalized),
                    reason = "looked in resources rooted at $rscRoot")
            }
            return object : SourceCode() {
                override val isFromResources = true
                override val origin = "$libraryFilePrefix$normalized"
                override fun getCharStream(): CharStream {
                    val inpStr = object {}.javaClass.getResourceAsStream(normalized)
                    return CharStreams.fromStream(inpStr)
                }
            }
        }

        // TODO: possibly more, like fromURL(..)
/*      // For `jar:..` URLs
        // see https://stackoverflow.com/questions/22605666/java-access-files-in-jar-causes-java-nio-file-filesystemnotfoundexception
        var url = URL("jar:file:/E:/x16/prog8(meisl)/compiler/build/libs/prog8compiler-7.0-BETA3-all.jar!/prog8lib/c64/textio.p8")
        val uri = url.toURI()
        val parts = uri.toString().split("!")
        val fs = FileSystems.newFileSystem(URI.create(parts[0]), mutableMapOf(Pair("", "")) )
        val path = fs.getPath(parts[1])
*/
    }
}