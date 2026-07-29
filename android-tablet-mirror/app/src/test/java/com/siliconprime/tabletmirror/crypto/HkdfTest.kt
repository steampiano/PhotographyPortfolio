package com.siliconprime.tabletmirror.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Checked against the SHA-256 test vectors in RFC 5869 appendix A, so the
 * implementation is verified against the specification rather than against
 * itself.
 */
class HkdfTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `rfc5869 case 1 basic`() {
        val ikm = hex("0b".repeat(22))
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")

        val prk = Hkdf.extract(salt, ikm)
        assertArrayEquals(
            hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"),
            prk,
        )
        assertArrayEquals(
            hex(
                "3cb25f25faacd57a90434f64d0362f2a" +
                    "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                    "34007208d5b887185865",
            ),
            Hkdf.expand(prk, info, 42),
        )
    }

    @Test
    fun `rfc5869 case 2 longer inputs`() {
        val ikm = hex((0..79).joinToString("") { "%02x".format(it) })
        val salt = hex((0x60..0xaf).joinToString("") { "%02x".format(it) })
        val info = hex((0xb0..0xff).joinToString("") { "%02x".format(it) })

        val prk = Hkdf.extract(salt, ikm)
        assertArrayEquals(
            hex("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244"),
            prk,
        )
        assertArrayEquals(
            hex(
                "b11e398dc80327a1c8e7f78c596a4934" +
                    "4f012eda2d4efad8a050cc4c19afa97c" +
                    "59045a99cac7827271cb41c65e590e09" +
                    "da3275600c2f09b8367793a9aca3db71" +
                    "cc30c58179ec3e87c14c01d5c1f3434f" +
                    "1d87",
            ),
            Hkdf.expand(prk, info, 82),
        )
    }

    @Test
    fun `rfc5869 case 3 empty salt and info`() {
        val ikm = hex("0b".repeat(22))
        val prk = Hkdf.extract(ByteArray(0), ikm)
        assertArrayEquals(
            hex("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04"),
            prk,
        )
        assertArrayEquals(
            hex(
                "8da4e775a563c18f715f802a063c5a31" +
                    "b8a11f5c5ee1879ec3454e5f3c738d2d" +
                    "9d201395faa4b61a96c8",
            ),
            Hkdf.expand(prk, ByteArray(0), 42),
        )
    }

    @Test
    fun `distinct info labels give independent output from the same secret`() {
        val prk = Hkdf.extract(ByteArray(16), ByteArray(32) { 7 })
        val a = Hkdf.expand(prk, "direction a".toByteArray(), 32)
        val b = Hkdf.expand(prk, "direction b".toByteArray(), 32)
        // This is exactly what keeps the two directions' keys separate.
        assertEquals(false, a.contentEquals(b))
    }

    @Test
    fun `output length is honoured across block boundaries`() {
        val prk = Hkdf.extract(ByteArray(16), ByteArray(32))
        for (length in intArrayOf(1, 31, 32, 33, 64, 65, 200)) {
            assertEquals(length, Hkdf.expand(prk, "x".toByteArray(), length).size)
        }
    }

    @Test
    fun `expanding beyond the hkdf limit is refused`() {
        val prk = Hkdf.extract(ByteArray(16), ByteArray(32))
        assertThrows(IllegalArgumentException::class.java) {
            Hkdf.expand(prk, ByteArray(0), 255 * 32 + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Hkdf.expand(prk, ByteArray(0), 0)
        }
    }

    @Test
    fun `derive matches extract then expand`() {
        val salt = ByteArray(16) { 3 }
        val ikm = ByteArray(32) { 9 }
        assertArrayEquals(
            Hkdf.expand(Hkdf.extract(salt, ikm), "label".toByteArray(), 48),
            Hkdf.derive(salt, ikm, "label", 48),
        )
    }
}
