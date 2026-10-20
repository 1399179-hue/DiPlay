package com.shilapi.xcertplay.fakeiphone

import com.shilapi.xcertplay.airplay.AirPlayCrypto
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.ControlCipher
import com.shilapi.xcertplay.airplay.Srp6aClient
import com.shilapi.xcertplay.airplay.Tlv8Codec
import com.shilapi.xcertplay.airplay.Tlv8Item
import com.shilapi.xcertplay.airplay.asciiBytes
import com.shilapi.xcertplay.airplay.concatBytes
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Fake iPhone —— 站在「iPhone 作为 AirPlay/CarPlay 源」的视角，对车机上的接收端（我们的
 * AirPlayServer / CarPlayController）发起协议。
 *
 * 目的：在**没有真 iPhone、没有车机**的情况下，回归测试我们协议栈的以下被测对象：
 *   - AirPlaySession 的 RTSP 路由（OPTIONS / GET /info / pair-setup / pair-verify / SETUP）
 *   - 配对与加解密（SRP-6a + Ed25519 + X25519，复用 AirPlayCrypto）
 *   - pair-verify 后的加密控制通道（服务端 ControlKeys 的 read/write label 是交叉的：
 *     服务器解密入站用 HKDF("Control-Write-Encryption-Key")，本客户端 encrypt 用同一把 ✓）
 *   - type-130 隧道（IapTunnel）：SETUP 协商 dataPort → 派生 DataStream-Output 密钥 →
 *     TCP 连接 → ChaCha20-Poly1305 帧 → APTransportPackage("comm") iAP2 包
 *
 * 注意（与 AllPlay tools/fake_phone.cpp 一致）：本工具**跳过 MFi /auth-setup**，因为那需要
 * MFi 身份文件（identity/）。真实 iPhone 这一步是强制的，所以本工具**不能替代真机验收**。
 *
 * ⚠️ 本模块自带一份纯 JVM 的协议/crypto 副本（同包 com.shilapi.xcertplay.airplay），因为
 *    :shared 是 Android 库、不能作为 JVM 依赖。长期应抽到独立 :crypto 模块。
 */
class FakeIphone(
    private val host: String = "127.0.0.1",
    private val port: Int = 7000,
    private val log: (String) -> Unit = { println(it) },
) {
    private val socket = Socket(host, port)
    private val out = BufferedOutputStream(socket.getOutputStream())
    private val inp = BufferedInputStream(socket.getInputStream())
    private var cseq = 1

    // ---- 加密控制通道状态（pair-verify 完成后启用） ----
    private var control: ControlCipher? = null
    private var cipherPending = ByteArray(0) // 尚未凑满一帧的密文尾巴
    private var plain = ByteArray(0) // 已解密待解析明文
    private var plainPos = 0

    /** 从 socket 读一批字节，必要时解密，追加进明文缓冲。 */
    private fun fillPlain(): Boolean {
        val chunk = ByteArray(READ_CHUNK_BYTES)
        val n = inp.read(chunk)
        if (n < 0) return false
        val c = control
        if (c == null) {
            appendPlain(chunk.copyOf(n))
        } else {
            val joined = concatBytes(cipherPending, chunk.copyOf(n))
            val dec = c.decrypt(joined)
            cipherPending = dec.rest
            appendPlain(dec.data)
        }
        return true
    }

    private fun appendPlain(bytes: ByteArray) {
        plain = if (plainPos == 0) {
            concatBytes(plain, bytes)
        } else {
            concatBytes(plain.copyOfRange(plainPos, plain.size), bytes)
        }
        plainPos = 0
    }

    private fun nextByte(): Int {
        while (plainPos >= plain.size) {
            if (!fillPlain()) return -1
        }
        return plain[plainPos++].toInt() and 0xff
    }

    /** 发一条 RTSP 请求并读回完整响应（状态行 + 头 + body）。pair-verify 后自动走加密帧。 */
    private fun request(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): RtspResponse {
        val head = StringBuilder()
        head.append("$method $path RTSP/1.0\r\n")
        head.append("CSeq: ${cseq++}\r\n")
        headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
        if (body != null) head.append("Content-Length: ${body.size}\r\n")
        head.append("\r\n")
        val wire = concatBytes(head.toString().toByteArray(StandardCharsets.US_ASCII), body ?: ByteArray(0))
        val c = control
        out.write(c?.encrypt(wire) ?: wire)
        out.flush()

        val statusLine = readLine()
        val respHeaders = mutableMapOf<String, String>()
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) respHeaders[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        val len = respHeaders["Content-Length"]?.toIntOrNull() ?: 0
        val bodyBytes = ByteArray(len)
        var off = 0
        while (off < len) {
            val b = nextByte()
            if (b < 0) error("对端在响应 body 中途 EOF")
            bodyBytes[off++] = b.toByte()
        }
        return RtspResponse(statusLine, respHeaders, bodyBytes)
    }

    private fun readLine(): String {
        val sb = StringBuilder()
        var c = nextByte()
        while (c != -1 && c != '\n'.code) {
            if (c != '\r'.code) sb.append(c.toChar())
            c = nextByte()
        }
        return sb.toString()
    }

    /** Phase 1：探活 + 读设备信息。 */
    fun phase1Probe(): DeviceInfo {
        val opt = request("OPTIONS", "/")
        log("OPTIONS -> $opt")
        val info = request("GET", "/info")
        val dict = BplistCodec.decode(info.body) as? Map<*, *>
        val deviceId = dict?.get("deviceID")?.toString()
        val name = dict?.get("name")?.toString()
        val features = dict?.get("features")?.toString()
        log("GET /info -> deviceID=$deviceId name=$name features=$features")
        return DeviceInfo(deviceId, name, features)
    }

    /**
     * Phase 2：完整配对（pair-setup + pair-verify），返回控制通道加解密器 + X25519 shared secret。
     * 严格镜像 shared/PairSetup.kt 与 PairVerify.kt 的服务端流程。
     */
    fun phase2PairAndVerify(controllerId: String = "FAKE-IPHONE-0001"): PairResult {
        // ---- pair-setup ----
        // state 1: 客户端发起
        val s1 = pairingRequest("/pair-setup", Tlv8Codec.encode(listOf(Tlv8Item(0x06, byteArrayOf(1)))))
        val m1 = Tlv8Codec.decode(s1)
        require(m1[0x06]?.firstOrNull()?.toInt()?.and(0xff) == 2) { "pair-setup: 期望 state=2，实际 ${m1[0x06]?.firstOrNull()}" }
        val serverB = m1[0x03] ?: error("pair-setup: 缺 PUBLIC_KEY(B)")
        val salt = m1[0x02] ?: error("pair-setup: 缺 SALT")

        val (a, aPublic) = Srp6aClient.generateA()
        val proof = Srp6aClient.finish(a, salt, serverB)
        val s3 = pairingRequest(
            "/pair-setup",
            Tlv8Codec.encode(
                listOf(
                    Tlv8Item(0x06, byteArrayOf(3)),
                    Tlv8Item(0x03, aPublic),
                    Tlv8Item(0x04, proof.m1),
                ),
            ),
        )
        val m3 = Tlv8Codec.decode(s3)
        require(m3[0x06]?.firstOrNull()?.toInt()?.and(0xff) == 4) { "pair-setup: 期望 state=4" }
        val serverM2 = m3[0x04] ?: error("pair-setup: 缺服务器证明 M2")
        // 客户端验证服务器证明：M2 == H(A, M1, K)
        val expectedM2 = AirPlayCrypto.sha512(proof.aPublic, proof.m1, proof.sessionKey)
        require(serverM2.contentEquals(expectedM2)) { "pair-setup: 服务器证明校验失败" }
        log("pair-setup: SRP 校验通过，sessionKey=${proof.sessionKey.toHexString().take(16)}…")

        // state 5: 交换长期公钥（客户端生成 ed25519 身份）
        val clientId = controllerId.toByteArray(StandardCharsets.UTF_8)
        val clientLt = AirPlayCrypto.ed25519Generate()
        val controllerSignKey = AirPlayCrypto.hkdfSha512(
            proof.sessionKey,
            "Pair-Setup-Controller-Sign-Salt".asciiBytes(),
            "Pair-Setup-Controller-Sign-Info".asciiBytes(),
        )
        val controllerSignData = concatBytes(proof.aPublic, clientId, clientLt.publicKey)
        val controllerSignature = AirPlayCrypto.ed25519Sign(clientLt.privateKey, controllerSignData)
        val encKey = AirPlayCrypto.hkdfSha512(
            proof.sessionKey,
            "Pair-Setup-Encrypt-Salt".asciiBytes(),
            "Pair-Setup-Encrypt-Info".asciiBytes(),
        )
        val sub5 = Tlv8Codec.encode(
            listOf(
                Tlv8Item(0x01, clientId),
                Tlv8Item(0x03, clientLt.publicKey),
                Tlv8Item(0x0a, controllerSignature),
            ),
        )
        val sealed5 = AirPlayCrypto.chachaSeal(encKey, AirPlayCrypto.nonceLabel("PS-Msg05"), sub5)
        val s5 = pairingRequest(
            "/pair-setup",
            Tlv8Codec.encode(
                listOf(
                    Tlv8Item(0x06, byteArrayOf(5)),
                    Tlv8Item(0x05, sealed5),
                ),
            ),
        )
        val m5 = Tlv8Codec.decode(s5)
        require(m5[0x06]?.firstOrNull()?.toInt()?.and(0xff) == 6) { "pair-setup: 期望 state=6" }
        val sealed6 = m5[0x05] ?: error("pair-setup: 缺 ENCRYPTED_DATA(state6)")
        val sub6 = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(encKey, AirPlayCrypto.nonceLabel("PS-Msg06"), sealed6))
        val accessoryId = sub6[0x01] ?: error("pair-setup: 缺 accessory ID")
        val accessoryLtpk = sub6[0x03] ?: error("pair-setup: 缺 accessory 长期公钥")
        val accessorySignature = sub6[0x0a] ?: error("pair-setup: 缺 accessory 签名")
        val accessorySignKey = AirPlayCrypto.hkdfSha512(
            proof.sessionKey,
            "Pair-Setup-Accessory-Sign-Salt".asciiBytes(),
            "Pair-Setup-Accessory-Sign-Info".asciiBytes(),
        )
        val accessorySignData = concatBytes(accessorySignKey, accessoryId, accessoryLtpk)
        require(AirPlayCrypto.ed25519Verify(accessoryLtpk, accessorySignData, accessorySignature)) {
            "pair-setup: accessory 签名校验失败"
        }
        log("pair-setup: 完成，已学习 accessory 长期公钥（${accessoryLtpk.toHexString().take(16)}…）")

        // ---- pair-verify ----
        val clientEph = AirPlayCrypto.x25519Generate()
        val v1 = pairingRequest(
            "/pair-verify",
            Tlv8Codec.encode(
                listOf(
                    Tlv8Item(0x06, byteArrayOf(1)),
                    Tlv8Item(0x03, clientEph.publicKey),
                ),
            ),
        )
        val vm1 = Tlv8Codec.decode(v1)
        require(vm1[0x06]?.firstOrNull()?.toInt()?.and(0xff) == 2) { "pair-verify: 期望 state=2" }
        val serverEphPub = vm1[0x03] ?: error("pair-verify: 缺服务器临时公钥")
        val vEncrypted = vm1[0x05] ?: error("pair-verify: 缺 ENCRYPTED_DATA")
        val shared = AirPlayCrypto.x25519Shared(clientEph.privateKey, serverEphPub)
        val vEncKey = AirPlayCrypto.hkdfSha512(
            shared,
            "Pair-Verify-Encrypt-Salt".asciiBytes(),
            "Pair-Verify-Encrypt-Info".asciiBytes(),
        )
        val vSub2 = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(vEncKey, AirPlayCrypto.nonceLabel("PV-Msg02"), vEncrypted))
        val serverId = vSub2[0x01] ?: error("pair-verify: 缺服务器 ID")
        val serverSignature = vSub2[0x0a] ?: error("pair-verify: 缺服务器签名")
        val serverSignData = concatBytes(serverEphPub, serverId, clientEph.publicKey)
        require(AirPlayCrypto.ed25519Verify(accessoryLtpk, serverSignData, serverSignature)) {
            "pair-verify: 服务器签名校验失败"
        }
        log("pair-verify: 服务器签名通过")

        // 客户端签名并发回
        val clientSignature = AirPlayCrypto.ed25519Sign(
            clientLt.privateKey,
            concatBytes(clientEph.publicKey, clientId, serverEphPub),
        )
        val vSub3 = Tlv8Codec.encode(
            listOf(
                Tlv8Item(0x01, clientId),
                Tlv8Item(0x0a, clientSignature),
            ),
        )
        val sealed3 = AirPlayCrypto.chachaSeal(vEncKey, AirPlayCrypto.nonceLabel("PV-Msg03"), vSub3)
        val v3 = pairingRequest(
            "/pair-verify",
            Tlv8Codec.encode(
                listOf(
                    Tlv8Item(0x06, byteArrayOf(3)),
                    Tlv8Item(0x05, sealed3),
                ),
            ),
        )
        val vm3 = Tlv8Codec.decode(v3)
        require(vm3[0x06]?.firstOrNull()?.toInt()?.and(0xff) == 4) { "pair-verify: 期望 state=4（完成）" }

        // 派生控制通道密钥。服务端 PairVerify.m4 的 label 是交叉的：
        //   服务器 readKey(解密入站) = HKDF("Control-Write-Encryption-Key") ← 客户端 encrypt 用它
        //   服务器 writeKey(加密出站) = HKDF("Control-Read-Encryption-Key")  ← 客户端 decrypt 用它
        val clientDecryptKey = AirPlayCrypto.hkdfSha512(
            shared,
            "Control-Salt".asciiBytes(),
            "Control-Read-Encryption-Key".asciiBytes(),
        )
        val clientEncryptKey = AirPlayCrypto.hkdfSha512(
            shared,
            "Control-Salt".asciiBytes(),
            "Control-Write-Encryption-Key".asciiBytes(),
        )
        log("pair-verify: 完成，控制通道密钥已派生（客户端 encrypt=服务器 readKey ✓）")
        return PairResult(ControlCipher(clientDecryptKey, clientEncryptKey), shared)
    }

    /**
     * Phase 3：type-130 iAP2 隧道端到端（纯 JVM 可测）。
     *
     * 镜像 CarPlayMediaEngine.onDataStream / IapTunnel 的服务端行为：
     *  1. 加密控制通道上发 SETUP（streams[type=130, clientTypeUUID=IAP 数据流 UUID, seed]）；
     *  2. 从响应读 dataPort；
     *  3. 派生隧道密钥 HKDF-SHA512(shared, "DataStream-Salt$seed", "DataStream-Output-Encryption-Key", 32)；
     *  4. TCP 连接 dataPort，发一个 ChaCha 帧（nonce=0，AAD=2 字节 LE 长度头），
     *     帧内是 32 字节 APTransportPackage 头（size BE@0，"comm"@16）+ iAP2 CSM body。
     */
    fun phase3OpenType130Tunnel(
        pair: PairResult,
        seed: Long = 0x5A5A5A,
        streamConnectionId: Long = 1L,
        iap2Payload: ByteArray = DEFAULT_IAP2_PROBE,
    ): TunnelResult {
        // 服务端在发出 pair-verify state4 响应后立即切到加密读 ⇒ 这里必须同步切换。
        control = pair.control
        log("控制通道已切换为加密（后续 RTSP 走 ChaCha 帧）")

        val setupBody = BplistCodec.encode(
            linkedMapOf<String, Any?>(
                "streams" to listOf<Any?>(
                    linkedMapOf<String, Any?>(
                        "type" to 130L,
                        "clientTypeUUID" to IAP_DATASTREAM_UUID,
                        "seed" to seed,
                        "streamConnectionID" to streamConnectionId,
                    ),
                ),
            ),
        )
        val resp = request(
            "SETUP",
            "/",
            headers = mapOf("Content-Type" to "application/x-apple-binary-plist"),
            body = setupBody,
        )
        log("SETUP type=130 -> ${resp.statusLine}")
        val dict = BplistCodec.decode(resp.body) as? Map<*, *> ?: error("SETUP 响应不是 bplist dict")
        val streams = dict["streams"] as? List<*> ?: error("SETUP 响应缺 streams")
        val s0 = streams.firstOrNull() as? Map<*, *> ?: error("SETUP 响应缺 stream 条目")
        val dataPort = (s0["dataPort"] as? Number)?.toInt() ?: error("SETUP 响应缺 dataPort")
        log("iAP 隧道已宣告 dataPort=$dataPort streamID=${s0["streamID"]}")

        val key = AirPlayCrypto.hkdfSha512(
            pair.sharedSecret,
            "DataStream-Salt$seed".asciiBytes(),
            "DataStream-Output-Encryption-Key".asciiBytes(),
            32,
        )
        val pkg = apTransportPackage(iap2Payload)
        Socket(host, dataPort).use { tunnelSocket ->
            val tout = tunnelSocket.getOutputStream()
            val header = byteArrayOf((pkg.size and 0xff).toByte(), ((pkg.size shr 8) and 0xff).toByte())
            tout.write(header)
            tout.write(AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(0), pkg, header))
            tout.flush()
            log("已发送 1 个 type-130 comm 包（明文 ${pkg.size}B，ChaCha 帧序号 0）")
            // 给服务端时间走完 读取→解密→APTransportPackage 解析→onIap 回调
            Thread.sleep(600)
        }
        return TunnelResult(dataPort, pkg.size)
    }

    /** 32 字节 APTransportPackage 头：u32 BE 总长@0，"comm"(0x636f6d6d)@16，body 跟在 @32。 */
    private fun apTransportPackage(iap2Body: ByteArray): ByteArray {
        val total = PACKAGE_HEADER_LEN + iap2Body.size
        val pkg = ByteArray(total)
        pkg[0] = ((total ushr 24) and 0xff).toByte()
        pkg[1] = ((total ushr 16) and 0xff).toByte()
        pkg[2] = ((total ushr 8) and 0xff).toByte()
        pkg[3] = (total and 0xff).toByte()
        "comm".asciiBytes().copyInto(pkg, MESSAGE_TYPE_OFFSET)
        iap2Body.copyInto(pkg, PACKAGE_HEADER_LEN)
        return pkg
    }

    /** 配对类请求：POST + application/pairing+tlv8。 */
    private fun pairingRequest(path: String, tlvBody: ByteArray): ByteArray {
        val resp = request(
            "POST",
            path,
            mapOf("Content-Type" to "application/pairing+tlv8"),
            tlvBody,
        )
        log("POST $path -> ${resp.statusLine} (${resp.body.size}B)")
        return resp.body
    }

    data class DeviceInfo(val deviceId: String?, val name: String?, val features: String?)
    data class RtspResponse(val statusLine: String, val headers: Map<String, String>, val body: ByteArray)

    /** Phase 2 的产物：控制通道加解密器（客户端视角）+ pair-verify 的 X25519 shared secret。 */
    data class PairResult(val control: ControlCipher, val sharedSecret: ByteArray)

    /** Phase 3 的产物。 */
    data class TunnelResult(val dataPort: Int, val packageBytes: Int)

    fun close() {
        runCatching { out.close() }
        runCatching { inp.close() }
        runCatching { socket.close() }
    }

    companion object {
        private const val READ_CHUNK_BYTES = 16 * 1024
        private const val PACKAGE_HEADER_LEN = 32
        private const val MESSAGE_TYPE_OFFSET = 16

        /** 与 CarPlayMediaEngine.IAP_DATASTREAM_UUID 一致。 */
        private const val IAP_DATASTREAM_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"

        /** 一个合法的空 iAP2 CSM 帧：0x4040 | len=6 | messageId=0。隧道对 iAP2 内容不做校验。 */
        private val DEFAULT_IAP2_PROBE = byteArrayOf(0x40, 0x40, 0x00, 0x06, 0x00, 0x00)
    }
}

fun main(args: Array<String>) {
    val host = args.getOrNull(0) ?: "127.0.0.1"
    val port = args.getOrNull(1)?.toIntOrNull() ?: 7000
    // probe = 只做 OPTIONS+/info；pair = +配对；tunnel = +type-130 隧道端到端
    val level = args.getOrNull(2) ?: "probe"
    val fake = FakeIphone(host, port)
    try {
        val info = fake.phase1Probe()
        println("probe ok: $info")
        if (level == "pair" || level == "tunnel") {
            val pair = fake.phase2PairAndVerify()
            println("pair ok")
            if (level == "tunnel") {
                val r = fake.phase3OpenType130Tunnel(pair)
                println("tunnel ok: $r")
            }
        }
    } finally {
        fake.close()
    }
}
