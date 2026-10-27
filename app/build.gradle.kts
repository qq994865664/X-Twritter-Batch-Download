plugins { id("com.android.application") }

android {
    namespace = "com.xbatch.download"
    compileSdk = 36
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
    defaultConfig {
        applicationId = "com.xbatch.download"
        minSdk = 29
        targetSdk = 36
        versionCode = 10
        versionName = "0.7.0"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    buildTypes {
        if (providers.gradleProperty("releaseVerification").isPresent) {
            getByName("debug").applicationIdSuffix = ".releasecheck"
            getByName("release").applicationIdSuffix = ".releasecheck"
        }
        create("probe") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".probe"
            matchingFallbacks += "debug"
        }
    }
    if (providers.gradleProperty("probeTests").isPresent) testBuildType = "probe"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
