@file:Suppress("UnstableApiUsage")

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import ru.vyarus.gradle.plugin.animalsniffer.AnimalSniffer
import ru.vyarus.gradle.plugin.animalsniffer.AnimalSnifferExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("multiplatform")
  id("com.android.library")
  kotlin("plugin.serialization")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
}

val platform = System.getProperty("okhttp.platform", "jdk9")
val testJavaVersion = System.getProperty("test.java.version", "21").toInt()

val copyKotlinTemplates = tasks.register<Copy>("copyKotlinTemplates") {
  val kotlinTemplatesOutput = layout.buildDirectory.dir("generated/sources/kotlinTemplates")

  from("src/commonJvmAndroid/kotlinTemplates")
  into(kotlinTemplatesOutput)

  filteringCharset = Charsets.UTF_8.toString()

  expand(
    // Build & use okhttp3/internal/-InternalVersion.kt
    "projectVersion" to project.version,
  )
}

// Build & use okhttp3/internal/idn/IdnaMappingTableInstance.kt
val generateIdnaMappingTableConfiguration: Configuration by configurations.creating
dependencies {
  generateIdnaMappingTableConfiguration(projects.okhttpIdnaMappingTable)
}
val generateIdnaMappingTable = tasks.register<JavaExec>("generateIdnaMappingTable") {
  val idnaOutput = layout.buildDirectory.dir("generated/sources/idnaMappingTable")

  outputs.dir(idnaOutput)
  mainClass.set("okhttp3.internal.idn.GenerateIdnaMappingTableCode")
  args(idnaOutput.get())
  classpath = generateIdnaMappingTableConfiguration
}

kotlin {
  jvmToolchain(8)

  jvm {
  }

  androidTarget {
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_17)
    }
  }

  sourceSets {
    val commonJvmAndroid = create("commonJvmAndroid") {
      dependsOn(commonMain.get())

      kotlin.srcDir(copyKotlinTemplates.map { it.outputs })
      kotlin.srcDir(generateIdnaMappingTable.map { it.outputs })

      dependencies {
        api(libs.squareup.okio)
        api(libs.kotlin.stdlib)

        compileOnly(libs.animalsniffer.annotations)
      }
    }

    commonTest {
      dependencies {
        implementation(projects.okhttpTestingSupport)
        implementation(libs.assertk)
        implementation(libs.kotlin.test.annotations)
        implementation(libs.kotlin.test.common)
        implementation(libs.kotlin.test.junit)
        implementation(libs.junit)
        implementation(libs.junit.jupiter.api)
        implementation(libs.junit.jupiter.params)
      }
    }

    androidMain {
      dependsOn(commonJvmAndroid)
      dependencies {
        compileOnly(libs.bouncycastle.bcprov)
        compileOnly(libs.bouncycastle.bctls)
        compileOnly(libs.conscrypt.openjdk)
        implementation(libs.androidx.annotation)
        implementation(libs.androidx.startup.runtime)
      }
    }

    jvmMain {
      dependsOn(commonJvmAndroid)

      dependencies {
        // These compileOnly dependencies must also be listed in applyOsgiMultiplatform() below.
        compileOnly(libs.conscrypt.openjdk)
        compileOnly(libs.bouncycastle.bcprov)
        compileOnly(libs.bouncycastle.bctls)

        // graal build support
        compileOnly(libs.nativeImageSvm)
        compileOnly(libs.openjsse)
      }
    }

    val jvmTest by getting {
      dependencies {
        implementation(projects.okhttpTestingSupport)
        implementation(libs.assertk)
        implementation(libs.kotlin.test.annotations)
        implementation(libs.kotlin.test.common)
        implementation(libs.kotlinx.serialization.core)
        implementation(libs.kotlinx.serialization.json)
        implementation(projects.okhttpJavaNetCookiejar)
        implementation(projects.okhttpTls)
        implementation(projects.okhttpUrlconnection)
        implementation(projects.mockwebserver3)
        implementation(projects.mockwebserver3Junit4)
        implementation(projects.mockwebserver3Junit5)
        implementation(projects.mockwebserver)
        implementation(projects.loggingInterceptor)
        implementation(projects.okhttpBrotli)
        implementation(projects.okhttpDnsoverhttps)
        implementation(projects.okhttpIdnaMappingTable)
        implementation(projects.okhttpSse)
        implementation(projects.okhttpCoroutines)
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.squareup.moshi)
        implementation(libs.squareup.moshi.kotlin)
        implementation(libs.squareup.okio.fakefilesystem)
        implementation(libs.conscrypt.openjdk)
        implementation(libs.junit)
        implementation(libs.junit.jupiter.api)
        implementation(libs.junit.jupiter.params)
        implementation(libs.kotlin.test.junit)
        implementation(libs.openjsse)

        implementation(libs.junit.jupiter.engine)
        implementation(libs.junit.vintage.engine)

        if (platform == "conscrypt") {
          implementation(rootProject.libs.conscrypt.openjdk)
        } else if (platform == "openjsse") {
          implementation(rootProject.libs.openjsse)
        }
      }
    }

    val androidUnitTest by getting {
      dependencies {
        implementation(libs.assertk)
        implementation(libs.kotlin.test.annotations)
        implementation(libs.kotlin.test.common)
        implementation(libs.androidx.junit)

        implementation(libs.junit.jupiter.engine)
        implementation(libs.junit.vintage.engine)

        implementation(libs.robolectric)
      }
    }
  }
}

if (platform == "jdk8alpn") {
  // Add alpn-boot on Java 8 so we can use HTTP/2 without a stable API.
  val alpnBootVersion = alpnBootVersion()
  if (alpnBootVersion != null) {
    val alpnBootJar = configurations.detachedConfiguration(
      dependencies.create("org.mortbay.jetty.alpn:alpn-boot:$alpnBootVersion")
    ).singleFile
    tasks.withType<Test> {
      jvmArgs("-Xbootclasspath/p:${alpnBootJar}")
    }
  }
}

android {
  compileSdk = 35

  namespace = "okhttp.okhttp3"

  defaultConfig {
    minSdk = 21

    consumerProguardFiles("okhttp3.pro")
  }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
    }
  }

  sourceSets {
    named("main") {
      manifest.srcFile("src/androidMain/AndroidManifest.xml")
      assets.srcDir("src/androidMain/assets")
    }
  }
}

project.applyOsgiMultiplatform(
  "Export-Package: okhttp3,okhttp3.internal.*;okhttpinternal=true;mandatory:=okhttpinternal",
  "Import-Package: " +
    "com.oracle.svm.core.annotate;resolution:=optional," +
    "com.oracle.svm.core.configure;resolution:=optional," +
    "dalvik.system;resolution:=optional," +
    "org.conscrypt;resolution:=optional," +
    "org.bouncycastle.*;resolution:=optional," +
    "org.openjsse.*;resolution:=optional," +
    "org.graalvm.nativeimage;resolution:=optional," +
    "org.graalvm.nativeimage.hosted;resolution:=optional," +
    "sun.security.ssl;resolution:=optional,*",
  "Automatic-Module-Name: okhttp3",
  "Bundle-SymbolicName: com.squareup.okhttp3",
)

val androidSignature by configurations.getting
val jvmSignature by configurations.getting

val checkstyleConfig: Configuration by configurations.named("checkstyleConfig")
dependencies {
  // Everything else requires Android API 21+.
  androidSignature(rootProject.libs.signature.android.apilevel21) { artifact { type = "signature" } }

  // OkHttp requires Java 8+.
  jvmSignature(rootProject.libs.codehaus.signature.java18) { artifact { type = "signature" } }

  checkstyleConfig(rootProject.libs.checkStyle) {
    isTransitive = false
  }
}

// Animal Sniffer confirms we generally don't use APIs not on Java 8.
configure<AnimalSnifferExtension> {
  annotation = "okhttp3.internal.SuppressSignatureCheck"
  defaultTargets("jvmMain", "debug")
}

project.tasks.withType<AnimalSniffer> {
  if (targetName == "animalsnifferJvmMain") {
    animalsnifferSignatures = jvmSignature
  } else {
    animalsnifferSignatures = androidSignature
  }
}

configure<CheckstyleExtension> {
  config = resources.text.fromArchiveEntry(checkstyleConfig, "google_checks.xml")
  toolVersion = rootProject.libs.versions.checkStyle.get()
  sourceSets = listOf(project.sourceSets["jvmMain"])
}

afterEvaluate {
  tasks.withType<Test> {
    if (javaLauncher.get().metadata.languageVersion.asInt() < 9) {
      // Work around robolectric requirements and limitations
      // https://cs.android.com/android-studio/platform/tools/base/+/mirror-goog-studio-main:build-system/gradle-core/src/main/java/com/android/build/gradle/tasks/factory/AndroidUnitTest.java;l=339
      allJvmArgs = allJvmArgs.filter { !it.startsWith("--add-opens") }
    }
    if (name.matches("test.*UnitTest".toRegex()) && javaLauncher.get().metadata.languageVersion.asInt() < 17) {
      // Work around robolectric requirements and limitations
      // https://github.com/robolectric/robolectric/issues/10419
      filter {
        excludeTest("okhttp3.internal.publicsuffix.PublicSuffixDatabaseTest", null)
      }
    }
  }
}

// Work around issue 8826, where the Sentry SDK assumes that OkHttp's internal-visibility symbols
// will be suffixed '$okhttp' in deployable artifacts. This isn't intended to be a published API,
// but it's easy enough for us to keep it working. https://github.com/square/okhttp/issues/8826
tasks.withType<KotlinCompile> {
  compilerOptions {
    freeCompilerArgs = listOf("-module-name=okhttp")
  }
}

apply(plugin = "io.github.usefulness.maven-sympathy")

mavenPublishing {
  configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), androidVariantsToPublish = listOf("release")))
}
