pluginManagement {
    repositories {
        // 国内镜像优先（本机无法直连 dl.google.com）
        if (System.getenv("GITHUB_ACTIONS") == null) {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
        }
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

// GitHub Actions（海外机房）上跳过国内镜像，直连官方仓库更快更稳
val useCnMirrors = System.getenv("GITHUB_ACTIONS") == null

dependencyResolutionManagement {
    repositories {
        if (useCnMirrors) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
        }
        mavenCentral()
        google()
    }
}

rootProject.name = "YunX-Desktop"
include(":desktop")
