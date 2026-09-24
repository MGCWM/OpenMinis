// [T-cn-mirrors] The Chinese mirrors below are a local-build convenience: they
// are reachable from mainland China without a proxy, where the official
// repositories are not. CI runs outside that network and can reach the official
// repositories directly, so there the list is pointless overhead — and some
// mirrors answer 404 for plugin markers that only exist on Maven Central, which
// makes plugin resolution fail confusingly. Set MINIS_BUILD_MIRRORS=off to skip
// them; google() / mavenCentral() / gradlePluginPortal() are always present.
val cnMavenMirrors: List<String> =
    if ((System.getenv("MINIS_BUILD_MIRRORS") ?: "on").lowercase() == "off") {
        emptyList()
    } else {
        listOf(
            "https://maven.aliyun.com/repository/google",
            "https://maven.aliyun.com/repository/public",
            "https://maven.aliyun.com/repository/gradle-plugin",
            "https://maven.aliyun.com/repository/central",
            "https://repo.huaweicloud.com/repository/maven",
            "https://mirrors.cloud.tencent.com/nexus/repository/maven-public",
        )
    }

pluginManagement {
    repositories {
        cnMavenMirrors.forEach { u -> maven { url = uri(u) } }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        cnMavenMirrors.forEach { u -> maven { url = uri(u) } }
        google()
        mavenCentral()
        // [T-android-vad] RealTimeCutVADLibraryForAndroid ships via JitPack
        // only. Same author and same underlying stack (Silero + ONNX Runtime +
        // WebRTC APM) as the RealTimeCutVADLibrary SPM package iOS already
        // uses, so both platforms segment speech with the same model and the
        // same tunables.
        maven { url = uri("https://jitpack.io") }
        // rclone.aar — the backup feature's remote destinations (SMB / WebDAV /
        // SFTP / S3 / FTP). Not published to any Maven repo: it is built from
        // deps/rclone-mobile by `deps/build_rclone_android.sh`, which is also
        // what produces the iOS XCFramework from the same Go sources and the
        // same trimmed backend list. Treated as a build artifact, not a vendored
        // binary — see docs/backup-restore-design.md §6.2.
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "Minis"
include(":app")
