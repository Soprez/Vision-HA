// SPDX-License-Identifier: MIT
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories {
        maven { url = uri("/tmp/localrepo") }
        google(); mavenCentral()
    }
}
rootProject.name = "VisionAndroid"
include(":app")
