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

    sourceSets {
        val desktopMain by getting

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

            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.cio)
            implementation(libs.kotlinx.coroutines.swing)

            // Embedded HTTP server — desktop only (serves fuel data to mobile devices on LAN)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.server.cors)

            implementation(libs.sqldelight.jvm)
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
