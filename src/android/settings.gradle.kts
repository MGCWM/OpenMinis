// [T-cn-mirrors] The Chinese Maven mirrors are a local-build convenience: they
// are reachable from mainland China without a proxy, where the official
// repositories are not. CI runs outside that network, so it sets
// MINIS_BUILD_MIRRORS=off and uses google() / mavenCentral() /
// gradlePluginPortal() directly — some mirrors also answer 502 or 404 from
// outside China, which turns plugin resolution into a confusing failure.
//
// The switch is inlined at each use site on purpose: a top-level val in this
// script is not visible inside pluginManagement {}.

pluginManagement {
    repositories {
        if ((System.getenv("MINIS_BUILD_MIRRORS") ?: "on").lowercase() !in listOf("off", "false", "0", "no")) {
            listOf(
                "https://maven.aliyun.com/repository/google",
                "https://maven.aliyun.com/repository/public",
                "https://maven.aliyun.com/repository/gradle-plugin",
                "https://maven.aliyun.com/repository/central",
                "https://repo.huaweicloud.com/repository/maven",
                "https://mirrors.cloud.tencent.com/nexus/repository/maven-public",
            ).forEach { u -> maven { url = uri(u) } }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if ((System.getenv("MINIS_BUILD_MIRRORS") ?: "on").lowercase() !in listOf("off", "false", "0", "no")) {
            listOf(
                "https://maven.aliyun.com/repository/google",
                "https://maven.aliyun.com/repository/public",
                "https://maven.aliyun.com/repository/gradle-plugin",
                "https://maven.aliyun.com/repository/central",
                "https://repo.huaweicloud.com/repository/maven",
                "https://mirrors.cloud.tencent.com/nexus/repository/maven-public",
            ).forEach { u -> maven { url = uri(u) } }
        }
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
