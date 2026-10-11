package com.shilapi.xcertplay.platform

import android.os.Build
import java.util.Locale

/**
 * One head-unit identity.
 *
 * Only the field the receiver branches on is carried: [id] is compared against
 * [HeadUnitProfiles.LYNK_03] to decide whether firmware-only behaviour is available.
 */
data class HeadUnitProfile(
    val id: String,
    val displayName: String,
)

/**
 * Recognises the head unit whose firmware this build has been verified against.
 *
 * Detection is the one the Lynk build uses: a `cs11_a4` / `cs11a4` token in any of
 * brand/manufacturer/model/device/product, or that token inside the build fingerprint, marks the
 * DesaySV CS11_A4 unit shipped in the Lynk & Co 03. Anything else stays [GENERIC], so behaviour
 * that is only proven on that firmware is never applied by accident.
 *
 * Measured on the target car: `Build.BRAND=desaysv`, `Build.MANUFACTURER=DesaySV`,
 * `Build.MODEL=CS11_A4`, `Build.PRODUCT=geely_fx11`, `Build.DEVICE=msmnile_gvmq`,
 * `Build.FINGERPRINT=DesaySV/geely_fx11/msmnile_gvmq:11/...`. The `cs11_a4` token is therefore
 * present directly in [Build.MODEL].
 */
object HeadUnitProfiles {

    /** DesaySV CS11_A4: LYNK OS N / Snapdragon 8155 / Android 11. */
    val LYNK_03 = HeadUnitProfile(id = "lynk_03", displayName = "Lynk & Co 03")

    /** Unrecognised hardware: firmware-only behaviour stays switched off. */
    val GENERIC = HeadUnitProfile(id = "generic", displayName = "xcertplay · unrecognized device")

    private val CS11_TOKEN_IN_FINGERPRINT = Regex("(?:^|[/._-])cs11_?a4(?:$|[/.:_-])")

    private val LYNK_03_MODEL_NAMES = setOf("03", "lynk 03", "lynk & co 03", "领克03", "领克 03")

    fun current(): HeadUnitProfile = detect(
        brand = Build.BRAND.orEmpty(),
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        device = Build.DEVICE.orEmpty(),
        product = Build.PRODUCT.orEmpty(),
        fingerprint = Build.FINGERPRINT.orEmpty(),
    )

    fun detect(
        brand: String,
        manufacturer: String,
        model: String,
        device: String,
        product: String,
        fingerprint: String,
    ): HeadUnitProfile {
        val fields = listOf(brand, manufacturer, model, device, product)
            .map { it.lowercase(Locale.ROOT) }
        val isCs11A4 = fields.any { it == "cs11_a4" || it == "cs11a4" } ||
            CS11_TOKEN_IN_FINGERPRINT.containsMatchIn(fingerprint.lowercase(Locale.ROOT))
        val isLynk = fields.take(2).any { it.contains("lynk") || it.contains("领克") }
        val isLynk03Model = fields[2] in LYNK_03_MODEL_NAMES
        return if (isCs11A4 || (isLynk && isLynk03Model)) LYNK_03 else GENERIC
    }
}
