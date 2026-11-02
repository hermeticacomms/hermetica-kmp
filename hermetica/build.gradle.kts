import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.android.library")
    id("com.vanniktech.maven.publish")
}

val sdkVersion = providers.gradleProperty("VERSION_NAME").get()
val ktor = "3.0.3"

// The version is compiled in so events and push tokens report it.
val genVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/version")
    outputs.dir(out)
    doLast {
        val f = out.get().file("com/hermeticacomms/kmp/Version.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package com.hermeticacomms.kmp\n\ninternal const val SDK_VERSION = \"kmp-$sdkVersion\"\n")
    }
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } } // desktop, and where the common tests run fast
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain {
            kotlin.srcDir(genVersion)
            dependencies {
                implementation("io.ktor:ktor-client-core:$ktor")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("io.ktor:ktor-client-mock:$ktor")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
        androidMain.dependencies { implementation("io.ktor:ktor-client-okhttp:$ktor") }
        jvmMain.dependencies { implementation("io.ktor:ktor-client-okhttp:$ktor") }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:$ktor") }
    }
}

android {
    namespace = "com.hermeticacomms.kmp"
    compileSdk = 35
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

mavenPublishing {
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), sourcesJar = true, androidVariantsToPublish = listOf("release")))
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates("com.hermeticacomms", "hermetica-kmp", sdkVersion)
    pom {
        name.set("Hermetica Kotlin Multiplatform SDK")
        description.set("Events, identity, consent, push tokens and in-app messages for Hermetica, shared across Android and iOS.")
        url.set("https://github.com/hermeticacomms/hermetica-kmp")
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("hermeticacomms")
                name.set("Hermetica")
                url.set("https://github.com/hermeticacomms")
            }
        }
        scm {
            url.set("https://github.com/hermeticacomms/hermetica-kmp")
            connection.set("scm:git:https://github.com/hermeticacomms/hermetica-kmp.git")
            developerConnection.set("scm:git:ssh://git@github.com/hermeticacomms/hermetica-kmp.git")
        }
    }
}
