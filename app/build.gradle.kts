plugins {
    id("com.android.application")
}

// CI 에서는 GitHub run number 로 버전을 올린다. 로컬 빌드는 1.
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// 서명 정보는 환경변수로만 받는다(키스토어 파일은 저장소에 두지 않음).
val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.qa.autocapture"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.qa.autocapture"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
