package prog8.code.target.encodings

import com.github.michaelbull.result.fold
import prog8.code.core.Encoding
import prog8.code.core.IStringEncoding
import prog8.code.core.InternalCompilerException

object Encoder: IStringEncoding {
    override val defaultEncoding: Encoding = Encoding.ISO

    override fun encodeString(str: String, encoding: Encoding): List<UByte> {
        val coded = when(encoding) {
            Encoding.PETSCII -> PetsciiEncoding.encodePetscii(str, true)
            Encoding.SCREENCODES -> PetsciiEncoding.encodeScreencode(str, true)
            Encoding.ISO -> IsoEncoding.encode(str)
            Encoding.ATASCII -> AtasciiEncoding.encode(str)
            Encoding.ISO5 -> IsoCyrillicEncoding.encode(str)
            Encoding.ISO16 -> IsoEasternEncoding.encode(str)
            Encoding.CP437 -> Cp437Encoding.encode(str)
            Encoding.KATAKANA -> KatakanaEncoding.encode(str)
            Encoding.C64OS -> C64osEncoding.encode(str)
            else -> throw InternalCompilerException("unsupported encoding $encoding")
        }
        return coded.fold(
            failure = { throw it },
            success = { it }
        )
    }
    override fun decodeString(bytes: Iterable<UByte>, encoding: Encoding): String {
        val decoded = when(encoding) {
            Encoding.PETSCII -> PetsciiEncoding.decodePetscii(bytes, true)
            Encoding.SCREENCODES -> PetsciiEncoding.decodeScreencode(bytes, true)
            Encoding.ISO -> IsoEncoding.decode(bytes)
            Encoding.ATASCII -> AtasciiEncoding.decode(bytes)
            Encoding.ISO5 -> IsoCyrillicEncoding.decode(bytes)
            Encoding.ISO16 -> IsoEasternEncoding.decode(bytes)
            Encoding.CP437 -> Cp437Encoding.decode(bytes)
            Encoding.KATAKANA -> KatakanaEncoding.decode(bytes)
            Encoding.C64OS -> C64osEncoding.decode(bytes)
            else -> throw InternalCompilerException("unsupported encoding $encoding")
        }
        return decoded.fold(
            failure = { throw it },
            success = { it }
        )
    }
}
