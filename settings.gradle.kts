pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// sherpa-onnx AAR is a 47MB binary, not committed (see .gitignore app/libs/*.aar).
// Bootstrap it BEFORE project evaluation so implementation(files(...)) in app/build.gradle.kts
// resolves for both local and CI builds. Verified against the v2.0.1 release checksum.
val sherpaAarUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar"
val sherpaAarSha256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
val sherpaAar = rootDir.resolve("app/libs/sherpa-onnx-android-arm64-v8a.aar")
if (!sherpaAar.exists()) {
    println("aura: downloading sherpa-onnx AAR (first build)...")
    sherpaAar.parentFile.mkdirs()
    java.net.URI(sherpaAarUrl).toURL().openStream().use { input ->
        sherpaAar.outputStream().use { output -> input.copyTo(output) }
    }
}
if (!sherpaAar.isFile || sherpaAar.length() <= 1048576) {
    throw GradleException("aura: sherpa-onnx AAR missing or too small (${if (sherpaAar.isFile) sherpaAar.length() else "absent"} bytes)")
}
val sherpaDigest = java.security.MessageDigest.getInstance("SHA-256")
sherpaAar.inputStream().use { input ->
    val buf = ByteArray(1 shl 16)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        sherpaDigest.update(buf, 0, n)
    }
}
val sherpaSha = sherpaDigest.digest().joinToString("") { "%02x".format(it) }
if (sherpaSha != sherpaAarSha256) {
    throw GradleException("aura: sherpa-onnx AAR sha256 mismatch: got $sherpaSha want $sherpaAarSha256")
}
println("aura: sherpa-onnx AAR OK (${sherpaAar.length()} bytes, sha256 verified)")

rootProject.name = "aura-app"
include(":app")