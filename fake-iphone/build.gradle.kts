plugins {
    kotlin("jvm")
    application
}

group = "com.shilapi.xcertplay"
version = "0.1.0"

dependencies {
    // 注意：不要依赖 :shared —— 它是 com.android.library（含 NDK/aidl/minSdk25），
    // 不能作为纯 JVM 模块的依赖。本模块自带一份纯 JVM 的协议/crypto 副本（见
    // src/main/kotlin/com/shilapi/xcertplay/airplay/，仅去掉了 android.os.Build 一行）。
    // 长期更优解是把这些类抽到单独的 :crypto 纯 JVM 模块，让 :shared 与 :fake-iphone 共用。
    implementation(libs.bouncycastle)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.shilapi.xcertplay.fakeiphone.FakeIphoneKt")
}
