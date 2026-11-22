pluginManagement {
    repositories {
        // 阿里云镜像（与原全局 init 脚本一致；写进项目，构建自包含）
        maven { setUrl("https://maven.aliyun.com/repository/public") }
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        maven { setUrl("https://maven.aliyun.com/repository/gradle-plugin") }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 用 PREFER_SETTINGS：本机全局 init 脚本 D:\Android\.gradle\init.d\mirrors.gradle
    // 会向所有项目注入 maven 仓库，与 FAIL_ON_PROJECT_REPOS 冲突导致构建直接失败。
    // PREFER_SETTINGS 忽略项目级注入，仓库只认本文件声明。
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven { setUrl("https://maven.aliyun.com/repository/public") }
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        google()
        mavenCentral()
    }
}

rootProject.name = "CloudSaver"
include(":app")
