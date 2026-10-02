plugins {
    id("com.android.application")
}

android {
    namespace = "vn.softworld.readingrobot"
    compileSdk = 34

    defaultConfig {
        applicationId = "vn.softworld.readingrobot"
        minSdk = 24          // Android 7.0+: covers classroom tablets and Android-based robots
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // For a store/MDM build, add a signingConfig here. Debug builds are signed automatically.
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The web app in ../../app is the single source of truth: copy it into assets on every build.
val syncWebApp by tasks.registering(Copy::class) {
    from("../../app") {
        exclude("tools/**", "sw.js", "README.md")
    }
    into("src/main/assets")
}
tasks.named("preBuild") { dependsOn(syncWebApp) }
