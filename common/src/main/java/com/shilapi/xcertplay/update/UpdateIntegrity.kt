package com.shilapi.xcertplay.update

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/**
 * A downloaded APK must be the same app, signed by the same certificate set and newer than the
 * installed build. A matching SHA-256 is not enough by itself: the checksums file and the APK come
 * from the same release, so a replaced release can describe a perfectly consistent but foreign APK.
 */
internal object UpdateIntegrity {
    fun accepts(
        installedPackage: String,
        candidatePackage: String,
        installedCode: Long,
        candidateCode: Long,
        installedSigners: Set<String>,
        candidateSigners: Set<String>,
    ): Boolean = installedPackage == candidatePackage &&
        candidateCode > installedCode &&
        installedSigners.isNotEmpty() &&
        installedSigners == candidateSigners

    /** SHA-256 of each signing certificate, as a set so signer order cannot matter. */
    fun signerDigests(signatures: Array<out Signature>?): Set<String> {
        if (signatures == null) return emptySet()
        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.map { signature ->
            digest.digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    @Suppress("DEPRECATION")
    fun signers(info: PackageInfo): Set<String> {
        // GET_SIGNING_CERTIFICATES is Android 9+; older builds only expose the legacy signatures array.
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return signerDigests(signatures)
    }
}
