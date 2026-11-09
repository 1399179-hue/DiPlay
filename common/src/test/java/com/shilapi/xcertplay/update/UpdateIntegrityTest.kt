package com.shilapi.xcertplay.update

import android.content.pm.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpdateIntegrityTest {
    private val signers = setOf("a".repeat(64))

    @Test fun acceptsTheSameAppWithTheSameSignersAndANewerVersion() {
        assertTrue(
            UpdateIntegrity.accepts("com.shihab.diplay", "com.shihab.diplay", 47, 48, signers, signers),
        )
    }

    @Test fun refusesAForeignPackage() {
        assertFalse(
            UpdateIntegrity.accepts("com.shihab.diplay", "com.shihab.diplay.lynk", 47, 48, signers, signers),
        )
    }

    @Test fun refusesAForeignSigningCertificate() {
        assertFalse(
            UpdateIntegrity.accepts("com.shihab.diplay", "com.shihab.diplay", 47, 48, signers, setOf("b".repeat(64))),
        )
    }

    @Test fun refusesAnOlderOrEqualVersion() {
        assertFalse(UpdateIntegrity.accepts("com.shihab.diplay", "com.shihab.diplay", 47, 47, signers, signers))
        assertFalse(UpdateIntegrity.accepts("com.shihab.diplay", "com.shihab.diplay", 47, 46, signers, signers))
    }

    @Test fun refusesWhenNeitherSideHasSigners() {
        assertFalse(UpdateIntegrity.accepts("com.shihab.diplay", "com.shihab.diplay", 47, 48, emptySet(), emptySet()))
    }

    @Test fun signerDigestsIgnoreOrderAndDependOnTheCertificateBytes() {
        val first = Signature(byteArrayOf(1, 2, 3))
        val second = Signature(byteArrayOf(4, 5, 6))
        val left = UpdateIntegrity.signerDigests(arrayOf(first, second))
        val right = UpdateIntegrity.signerDigests(arrayOf(second, first))
        assertEquals(left, right)
        assertEquals(2, left.size)
        assertTrue(left.all { it.length == 64 })
        assertTrue(left != UpdateIntegrity.signerDigests(arrayOf(first)))
        assertEquals(emptySet<String>(), UpdateIntegrity.signerDigests(null))
    }
}
