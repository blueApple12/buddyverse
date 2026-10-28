import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.mp)
}

kotlin {
    jvmToolchain(21)
    jvm()
    sourceSets {
        getByName("jvmMain").resources.srcDir(layout.buildDirectory.dir("setup-res"))
        jvmMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.desktop.currentOs)
            implementation(libs.zxing)
        }
    }
}

// The Windows PC app (design §2.2). `packageExe` = per-user installer (no admin); `createDistributable` = portable folder.
compose.desktop {
    application {
        mainClass = "app.buddyverse.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "Buddyverse"
            packageVersion = "0.1.0"
            description = "Buddyverse"
            vendor = "Buddyverse"
            modules("java.naming", "jdk.crypto.ec", "jdk.unsupported", "java.management")
            windows {
                // The installer's build number (MSI major.minor.build). jpackage's MSI replaces only OLDER versions, so a
                // rebuilt installer for the same app release (0.1.0 Beta) needs the next build here to install over it.
                exePackageVersion = "0.1.1"
                perUserInstall = true
                menu = true
                menuGroup = "Buddyverse"
                shortcut = true
                dirChooser = false
                upgradeUuid = "ea652b9c-49cf-55ca-9771-1e08cdccf455"
                iconFile.set(project.file("icon.ico"))
            }
        }
    }
}

// First-run setup payload (Setup.kt): the web app plugin, the empty-vault template, the start script and a file list
// (a jar can't list a resource folder). Nothing user-specific: only files from this repo and the build.
val setupResources by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("setup-res/setup"))
    from(project(":shared").tasks.named("webPlugin")) { into("plugin") }
    from(project.file("setup"))
    exclude("files.txt")
    doLast {
        val root = destinationDir
        val list = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
        root.resolve("files.txt").writeText(list.joinToString("\n", postfix = "\n"))
    }
}
tasks.matching { it.name == "jvmProcessResources" }.configureEach { dependsOn(setupResources) }
