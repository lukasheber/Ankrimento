// SPDX-License-Identifier: GPL-3.0-or-later

/*
 Ankrimento: Incremento's unchanged Python backend embedded with Chaquopy.

 Python sources come from three places:
  - src/main/python: the bridge to AnkiDroid's backend and the `aqt` stand-in
  - Anki's Python library (`anki`), taken from the PyPI wheel whose version
    matches AnkiDroid's Rust backend, minus its native `_rsbridge`
  - vendor/incremento (git submodule), shipped as data and installed at runtime
    with the same layout as desktop's addons21/incremento
*/

import com.android.build.api.dsl.LibraryExtension
import com.ichi2.anki.gradle.addAnkiBackendDependencies
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

buildscript {
    dependencies {
        // Chaquopy finds AGP's version through a buildscript classpath, but this
        // project loads AGP from buildSrc. Same version, so the classes still come
        // from buildSrc (Gradle classloaders are parent-first).
        classpath("com.android.tools.build:gradle:${libs.versions.androidGradlePlugin.get()}")
    }
}

plugins {
    id("ankidroid.android.library")
    id("ankidroid.android.compose")
    id("com.chaquo.python") version "17.2.0"
}

// Python's `anki` must be built from the same Anki release as AnkiDroid's
// Rust backend: both index backend RPCs by position.
val ankiPythonVersion = "26.9.3"
val ankiWheelUrl =
    "https://files.pythonhosted.org/packages/e1/dc/585eee290ff22d3ea0a7067e16aa27bd9d1f4dbf8aee9397255eed8d3376/" +
        "anki-26.9.3-cp310-abi3-manylinux_2_35_x86_64.whl"
val ankiWheelSha256 = "7b3dcb6c9e06e8087ed66bb70f35356e5c60686f51e3bde25f6328433072132f"

fun normalizedVersion(version: String) = version.split(".").map { it.toInt() }

val backendAnkiVersion =
    libs.versions.ankiBackend
        .get()
        .substringAfter("-anki")
check(normalizedVersion(backendAnkiVersion) == normalizedVersion(ankiPythonVersion)) {
    "AnkiDroid's backend is Anki $backendAnkiVersion but ankrimento bundles Python anki $ankiPythonVersion. " +
        "Update ankiPythonVersion, ankiWheelUrl and ankiWheelSha256 in ankrimento/build.gradle.kts."
}

configure<LibraryExtension> {
    namespace = "com.ichi2.anki.ankrimento"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
    // As the app: java.time and java.nio below API 26 come from desugaring (lint checks against it).
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

val ankiWheel = layout.buildDirectory.file("ankrimento/anki-$ankiPythonVersion.whl")

val downloadAnkiWheel =
    tasks.register("downloadAnkiWheel") {
        // Plain values only: the configuration cache can't serialize script references.
        val wheelFile = ankiWheel
        val url = ankiWheelUrl
        val sha256 = ankiWheelSha256
        outputs.file(wheelFile)
        doLast {
            val wheel = wheelFile.get().asFile
            wheel.parentFile.mkdirs()
            URI(url).toURL().openStream().use { input ->
                wheel.outputStream().use { input.copyTo(it) }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(wheel.readBytes())
            val actual = digest.joinToString("") { "%02x".format(it) }
            check(actual == sha256) { "anki wheel checksum mismatch: $actual" }
        }
    }

val ankiPythonLibrary =
    tasks.register<Sync>("ankiPythonLibrary") {
        dependsOn(downloadAnkiWheel)
        from(zipTree(ankiWheel)) {
            include("anki/**")
            // Replaced at runtime by ankrimento.rsbridge, which forwards to AnkiDroid's backend.
            exclude("anki/_rsbridge*")
        }
        into(layout.buildDirectory.dir("ankrimento/pylib"))
    }

val incrementoAddonSource =
    tasks.register<Sync>("incrementoAddonSource") {
        from("src/addon/python")
        from(rootProject.file("vendor/incremento")) {
            // The Qt-free part of the add-on; see ankrimento.addon.SHIPPED_ENTRIES.
            include(
                "backend/**",
                "locales/**",
                "config.json",
                "manifest.json",
                "frontend/epub_dock.py",
                "frontend/epub_passages.py",
                "frontend/reader_coverage.py",
                "frontend/reader_links.py",
                "frontend/search_all_results.py",
                "frontend/stats_dashboard.py",
                "frontend/stats_dialog.py",
                "frontend/stats_progress.py",
                "frontend/writing_dock.py",
                "web/web_dock_bridge.js",
            )
            exclude("**/__pycache__/**", "**/AGENTS.md")
            into("ankrimento_addon_source/incremento")
        }
        // The sync add-on (vendor/incremento-sync), installed beside Incremento as on desktop; only its Qt-free part
        // ships (see ankrimento.addon.SYNC_ENTRIES).
        from(rootProject.file("vendor/incremento-sync/incremento_sync")) {
            include("engine/**", "locales/**", "loader.py", "patches.py", "host.py", "i18n.py", "manifest.json", "config.json")
            exclude("**/__pycache__/**")
            into("ankrimento_addon_source/incremento_sync")
        }
        into(layout.buildDirectory.dir("ankrimento/addon"))
    }

/** Copies upstream's web readers into generated APK assets under ankrimento/web. */
abstract class IncrementoWebAssets : DefaultTask() {
    @get:InputDirectory
    abstract val webDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(webDir) {
                include("pdf_dock.html", "video_player.html", "dist/**", "pdfjs/**")
                into("ankrimento/web")
            }
            into(outputDir)
        }
    }
}

val incrementoWebAssets =
    tasks.register<IncrementoWebAssets>("incrementoWebAssets") {
        webDir.set(rootProject.layout.projectDirectory.dir("vendor/incremento/web"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(incrementoWebAssets, IncrementoWebAssets::outputDir)
    }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        pip {
            // Requirements of anki 26.9.3, minus orjson (anki falls back to json).
            install("decorator")
            install("distro")
            install("markdown")
            install("protobuf>=6.0,<8.0")
            install("requests")
            install("typing-extensions")
            // Used by Incremento's backend.
            install("beautifulsoup4")
            // Backs the QPdfDocument stand-in (page count, password check), which replaces PyMuPDF and Qt.
            install("pypdf")
        }
        // Keep .py files so tracebacks from upstream code show source lines.
        pyc {
            src = false
        }
        // The add-on is copied out of the APK into addons21/ at startup.
        extractPackages("ankrimento_addon_source")
    }
    sourceSets {
        getByName("main") {
            srcDir(ankiPythonLibrary.map { it.destinationDir })
            srcDir(incrementoAddonSource.map { it.destinationDir })
        }
    }
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("PythonSources")) {
        dependsOn(ankiPythonLibrary, incrementoAddonSource)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
    // The learning loop's screens under Robolectric, as note-editor tests its Compose screens.
    testImplementation(libs.robolectric)
    testImplementation(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    implementation(project(":anki-common"))
    implementation(project(":common"))
    implementation(project(":libanki"))
    addAnkiBackendDependencies(project)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core)
    implementation(libs.androidx.webkit)
    implementation(libs.jakewharton.timber)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.protobuf.kotlin.lite)
}
