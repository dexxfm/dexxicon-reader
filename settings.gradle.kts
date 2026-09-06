@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "DexxiconReader"

include(":app")
include(":core:model")
include(":core:common")
include(":core:designsystem")
include(":core:datastore")
include(":core:network")
include(":core:security")
include(":core:database")
include(":core:serverapi")
include(":core:format")
include(":core:media")
include(":core:opds")
include(":core:data")
include(":feature:servers")
include(":feature:catalog")
include(":feature:library")
include(":feature:reader-epub")
include(":feature:reader-pdf")
include(":feature:reader-comic")
include(":feature:player")
include(":feature:annotations")
include(":feature:settings")
