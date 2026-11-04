package com.shilapi.xcertplay.airplay

// 纯 JVM 实现（2026-10-19）。我们 shared 里只有 SRP 服务端（Srp6a），客户端数学这里自写。
// 协议参数与 shared/Srp6a.kt 完全一致：RFC 5054 3072-bit 群、SHA-512、g=5、username="Pair-Setup"、password="3939"。
import java.math.BigInteger
import java.security.SecureRandom

/**
 * SRP-6a client (controller side) for AirPlay pair-setup.
 *
 * Flow (mirrors shared/Srp6aSession.verify, which is the server):
 *   x  = H(salt, H(username || ":" || password))
 *   u  = H(PAD(A), PAD(B))
 *   v  = g^x mod N
 *   S  = (B - k*v)^a mod N
 *   K  = H(S)
 *   M1 = H( H(N) XOR H(g), H(username), salt, A, B, K )   ← client proof sent to server
 *   M2 = H(A, M1, K)                                       ← server proof, client verifies it
 */
object Srp6aClient {
    private val random = SecureRandom()
    private val n = BigInteger(N_HEX, 16)
    private val g = BigInteger.valueOf(5)
    private val k = toBigInt(AirPlayCrypto.sha512(toBytes(n), pad(g)))
    private const val N_BYTES = 384

    /** Generate a fresh client ephemeral and return (private a, public A padded to 384 bytes). */
    fun generateA(): Pair<BigInteger, ByteArray> {
        val aBytes = ByteArray(32).also(random::nextBytes)
        val a = toBigInt(aBytes)
        val aPublic = g.modPow(a, n)
        return a to pad(aPublic)
    }

    /** Compute the client proof M1 and the session key K from the server's salt + public B. */
    fun finish(
        a: BigInteger,
        salt: ByteArray,
        serverB: ByteArray,
    ): ClientProof {
        val identifier = SETUP_USERNAME.toByteArray(Charsets.UTF_8)
        val pass = SETUP_CODE.toByteArray(Charsets.UTF_8)
        val x = toBigInt(AirPlayCrypto.sha512(salt, AirPlayCrypto.sha512(identifier, COLON, pass)))
        val v = g.modPow(x, n)

        val aPublic = g.modPow(a, n)
        val b = toBigInt(serverB)
        val u = toBigInt(AirPlayCrypto.sha512(pad(aPublic), pad(b)))
        val base = b.subtract(k.multiply(v)).mod(n)
        val s = base.modPow(a, n)
        val kSession = AirPlayCrypto.sha512(toBytes(s))

        val hashN = AirPlayCrypto.sha512(toBytes(n))
        val hashG = AirPlayCrypto.sha512(toBytes(g))
        val hashXor = ByteArray(hashN.size)
        for (i in hashN.indices) hashXor[i] = (hashN[i].toInt() xor hashG[i].toInt()).toByte()
        val m1 = AirPlayCrypto.sha512(
            hashXor,
            AirPlayCrypto.sha512(identifier),
            salt,
            pad(aPublic),
            pad(b),
            kSession,
        )
        return ClientProof(m1, kSession, pad(aPublic))
    }

    data class ClientProof(val m1: ByteArray, val sessionKey: ByteArray, val aPublic: ByteArray)

    internal fun toBytes(value: BigInteger): ByteArray {
        if (value.signum() == 0) return byteArrayOf(0)
        val encoded = value.toByteArray()
        return if (encoded.size > 1 && encoded[0] == 0.toByte()) encoded.copyOfRange(1, encoded.size) else encoded
    }

    internal fun toBigInt(bytes: ByteArray): BigInteger = BigInteger(1, bytes)

    internal fun pad(value: BigInteger): ByteArray {
        val bytes = toBytes(value)
        if (bytes.size >= N_BYTES) return bytes
        return ByteArray(N_BYTES).also { bytes.copyInto(it, N_BYTES - bytes.size) }
    }

    private const val N_HEX =
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
            "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
            "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
            "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
            "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183" +
            "995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A" +
            "85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7AB" +
            "F5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D8" +
            "7602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208" +
            "E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"

    private const val SETUP_USERNAME = "Pair-Setup"
    private const val SETUP_CODE = "3939"
    private val COLON = byteArrayOf(':'.code.toByte())
}
