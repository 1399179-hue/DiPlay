package com.shilapi.xcertplay.network

import java.io.IOException
import kotlin.text.Charsets

/**
 * Decides which hotspot name/passphrase to advertise.
 *
 * The car's TCAM module is the only truthful source on this head unit, but it can answer with a
 * name and no passphrase (or with nothing at all) depending on firmware and on whether the owner
 * changed the factory hotspot. The rules are, in order:
 *
 *  - a complete, valid pair from TCAM wins outright;
 *  - a valid TCAM name with a saved passphrase for the same name is accepted (the owner typed it);
 *  - a saved complete pair is accepted when TCAM produced no name;
 *  - anything else fails with a message naming what is missing, rather than advertising a
 *    placeholder identity the handset will silently refuse.
 */
internal object LynkTcamCredentials {

    /** Where the accepted pair came from; the diagnostic string is logged verbatim. */
    enum class Source(val diagnostic: String) {
        TCAM("tcam"),
        SAVED_PASSWORD("saved-field"),
        SAVED_PAIR("saved-pair"),
    }

    class Selected(
        val ssid: String,
        val passphrase: String,
        val source: Source,
    ) {
        override fun toString(): String = "TcamCredentials(source=${source.diagnostic})"
    }

    @Throws(IOException::class)
    fun select(
        liveSsid: String?,
        livePassphrase: String?,
        savedSsid: String?,
        savedPassphrase: String?,
    ): Selected {
        val liveName = liveSsid?.takeIf { it.isNotEmpty() }
        val liveSecret = livePassphrase?.takeIf { it.isNotEmpty() }

        if (liveName != null && !validName(liveName)) {
            throw IOException("领克热点名称无效，请检查原厂热点设置。")
        }
        if (liveSecret != null && !validPassword(liveSecret)) {
            throw IOException("领克热点密码无效，请检查原厂热点设置。")
        }
        if (liveName != null && liveSecret != null) {
            return Selected(liveName, liveSecret, Source.TCAM)
        }

        val savedName = savedSsid?.takeIf { validName(it) }
        val savedSecret = savedPassphrase?.takeIf { validPassword(it) }

        if (liveName != null) {
            if (savedName == liveName && savedSecret != null) {
                return Selected(liveName, savedSecret, Source.SAVED_PASSWORD)
            }
            throw IOException("原厂未提供热点密码，请在连接设置中保存与当前车载热点同名的名称和密码。")
        }
        if (liveSecret == null && savedName != null && savedSecret != null) {
            return Selected(savedName, savedSecret, Source.SAVED_PAIR)
        }
        throw IOException("原厂未提供完整热点配置，请检查并保存当前车载热点的名称和密码。")
    }

    /** 802.11 SSIDs are at most 32 bytes and cannot contain U+0000. */
    private fun validName(value: String): Boolean =
        value.isNotBlank() && '\u0000' !in value && value.toByteArray(Charsets.UTF_8).size <= 32

    /** WPA2 passphrases are 8..63 printable characters. */
    private fun validPassword(value: String): Boolean =
        value.length in 8..63 && '\u0000' !in value
}
