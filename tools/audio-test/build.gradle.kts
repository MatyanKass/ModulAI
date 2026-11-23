plugins {
    kotlin("jvm") version "2.0.21"
    application
}

kotlin {
    sourceSets["main"].kotlin {
        srcDir("../../core/runtime/src/main/java")
        srcDir("../../engines/audio/src/main/java")
        // The system speech engine is the only part that needs Android classes.
        exclude("**/SystemTtsEngine.kt")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // The desktop build of the library the app takes from onnxruntime-android; keep the versions equal.
    implementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")
    implementation("org.json:json:20240303")
}

layout.buildDirectory.set(file(providers.gradleProperty("out").orElse("build").get()))

application {
    mainClass.set("com.modulai.audiotest.AudioTestKt")
}

tasks.named<JavaExec>("run") {
    jvmArgs("-Dstdout.encoding=UTF-8")
    systemProperty("java.library.path", providers.gradleProperty("nativeDir").orElse("").get())
    args(providers.gradleProperty("models").orElse("").get(), providers.gradleProperty("samples").orElse("").get())
}
