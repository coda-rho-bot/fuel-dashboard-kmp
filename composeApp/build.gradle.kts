import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.angusGradleTools.coverage)
    alias(libs.plugins.sqldelight)
}

val appId = "com.angussoftware.fueldashboard"

angusCoverage {
    sourceRoots.set(
        listOf(
            projectDir.resolve("src/commonMain/kotlin").absolutePath,
        ),
    )
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    "**/R.class", "**/R\$*.class", "**/*R*.class",
                    "**/BuildConfig.*", "**/Manifest*.*",
                    "**/*Test*.*", "**/generated/**",
                    "**/*ComposableSingletons*",
                )
                packages(
                    "androidx", "androidx.**",
                    "android", "android.**",
                    "kotlin", "kotlin.**",
                    "kotlinx", "kotlinx.**",
                )
            }
        }
    }
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop")

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    sourceSets {
        val desktopMain by getting
        val desktopTest by getting

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.animation)
            implementation(compose.materialIconsExtended)

            implementation(libs.angusSoftware.theming.compose)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            implementation(libs.qrcode.kotlin)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.cio)

            // SLF4J provider — routes ACP/Ktor/kotlin-logging output to log file
            implementation(libs.slf4j.simple)

            // Embedded HTTP server — desktop only (serves fuel data to mobile devices on LAN)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.server.cors)

            implementation(libs.sqldelight.jvm)

            // ACP (Agent Client Protocol) — for monitoring Letta/Claude/Copilot agents
            implementation(libs.acp.kotlin)
            implementation(libs.acp.kotlin.ktor.client)

            // MCP (Model Context Protocol) — allows agents to self-register via standard MCP protocol
            implementation(libs.mcp.kotlin.server)
        }

        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.zxing.android.embedded)

            implementation(libs.sqldelight.android)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        desktopTest.dependencies {
            implementation(compose.uiTest)
            implementation(libs.ktor.server.test.host)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = appId
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = appId
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionName = project.version.toString()
        versionCode = providers.gradleProperty("android.versionCode").orElse("1").map(String::toInt).get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.angussoftware.fueldashboard.MainKt"

        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm,
            )
            // java.sql: SQLDelight JDBC loads DriverManager reflectively — jdeps can't see it
            // jdk.crypto.ec: EC crypto for HTTPS TLS — not always included in jlink suggestions
            modules("java.sql", "jdk.crypto.ec")
            packageName = "fuel-dashboard"
            packageVersion = project.version.toString()
            description = "Fuel Dashboard for Letta — AI provider fuel monitoring"
            vendor = "Angus Software"
            windows {
                menuGroup = "Angus Software"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            }
        }
    }
}

sqldelight {
    databases {
        create("FuelDatabase") {
            packageName.set("com.angussoftware.fueldashboard.database")
        }
    }
}

// Hermetic tests: the desktop SettingsStore actual backs onto
// Preferences.userRoot() (filesystem under $user.home/.java/.userprefs on
// Linux). Without isolation, tests constructing FuelViewModel would load the
// dev machine's REAL provider configs (live API keys) and re-import flows
// could start live polling. Redirect the test JVM's user.home into the build
// tree so the prefs node starts empty.
tasks.withType<Test>().configureEach {
    systemProperty("user.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
}

