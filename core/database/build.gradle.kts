import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

// KMP (issue #58). Room 2.8.4 (already what this project pins — well past 2.7, the version
// that added multiplatform support) supports @ConstructedBy/RoomDatabaseConstructor and iOS
// targets directly in the androidx.room package — no need for the newer, still-alpha Room
// 3.0 (androidx.room3) rewrite. @Database class, entities, DAOs, and all 8 migrations move
// to commonMain (ported from SupportSQLiteDatabase to SQLiteConnection — see
// DexxiconMigrations.kt). androidMain/iosMain each supply getDatabaseBuilder(); the Android
// one keeps the exact same context.getDatabasePath(NAME) path an existing install already
// uses, so upgrading users' databases open (and migrate) unchanged.
//
// No Hilt plugin here — same reason as every other KMP'd module: it refuses to apply to a
// KMP module at all. DatabaseModule (the @Module) moved to :app. The plain ksp plugin stays,
// though — Room's own KSP compiler needs to run per target (see the dependencies block).
kotlin {
    androidLibrary {
        namespace = "net.dexxicon.reader.core.database"
        compileSdk = 37
        minSdk = 29
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        // Opt-in required by the KMP androidLibrary DSL — plain unit tests (no device) live
        // in src/androidHostTest, not src/test as with the classic com.android.library plugin.
        withHostTestBuilder {}.configure {}
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(project(":core:common"))
            // api: DexxiconDatabase extends RoomDatabase, and :app's DatabaseModule
            // references it (and the builder type) directly.
            api(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
}
