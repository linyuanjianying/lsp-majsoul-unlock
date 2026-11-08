pluginManagement {
    repositories {
        // This large compiler artifact redirects to a GitHub release on the
        // primary repository, which currently returns HTTP 504 in CI.
        exclusiveContent {
            forRepository { maven("https://maven.aliyun.com/repository/central") }
            filter { includeModule("org.jetbrains.kotlin", "kotlin-compiler-embeddable") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // This large compiler artifact redirects to a GitHub release on the
        // primary repository, which currently returns HTTP 504 in CI.
        exclusiveContent {
            forRepository { maven("https://maven.aliyun.com/repository/central") }
            filter { includeModule("org.jetbrains.kotlin", "kotlin-compiler-embeddable") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "MajsoulHookProbe"

