import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The engine as a plain Kotlin library the Android app uses.
// Only src/main goes through Gradle. The checks in src/test and the CLI in src/cli
// keep running through ./run-tests.sh, unchanged.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        // Compile against the Java 8 API, which is what Android 8 (minSdk 26) has.
        // If the engine ever calls something newer, the build fails here instead of crashing on the phone.
        freeCompilerArgs.add("-Xjdk-release=1.8")
    }
    sourceSets.named("test") {
        kotlin.setSrcDirs(emptyList<String>())
    }
}
