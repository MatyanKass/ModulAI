import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    api(project(":core:runtime"))
    implementation(libs.coroutines.core)
    // Android ships org.json; the artifact is only needed to compile and to run tests on the JVM.
    compileOnly(libs.json)
    testImplementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
