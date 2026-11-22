plugins {
    id("com.android.application") version "9.0.0"
}

android {
    namespace = "net.micode.notes"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "net.micode.notes"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // 保持作业要求的 src / res 根目录结构，不迁移到 src/main。
    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.directories.add("src")
            res.directories.add("res")
        }
        getByName("test") {
            java.directories.add("test/java")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        abortOnError = false
    }

    // 旧同步模块仍引用 org.apache.http；先提供 legacy 库保证可编译，
    // M1 完成后该模块整体退役。
    useLibrary("org.apache.http.legacy")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // JVM 单测使用真实实现，运行时仍使用 Android 框架自带的 org.json
    testImplementation("org.json:json:20240303")
}
