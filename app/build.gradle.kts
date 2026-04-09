import java.io.FileInputStream
import java.util.Properties

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.kotlin.compose)
}

android {
	namespace = "app.myzel394.alibi"
	compileSdk = 35

	val keystoreProperties = Properties()
	val keystorePropertiesFile = rootProject.file("key.properties")
	if (keystorePropertiesFile.exists()) {
		keystoreProperties.load(FileInputStream(keystorePropertiesFile))
	}

	lint {
		disable += "ExtraTranslation"
	}

	androidResources {
		generateLocaleConfig = true
	}

	splits {
		abi {
			isEnable = true
			isUniversalApk = true
		}
	}

	defaultConfig {
		multiDexEnabled = true
		applicationId = "app.myzel394.alibi"
		minSdk = 24
		targetSdk = 35
		versionCode = 17
		versionName = "0.6.0"

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		vectorDrawables {
			useSupportLibrary = true
		}
	}

	signingConfigs {
		create("release") {
			keyAlias = keystoreProperties["keyAlias"] as? String
			keyPassword = keystoreProperties["keyPassword"] as? String
			storeFile = (keystoreProperties["storeFile"] as? String)?.let { file(it) }
			storePassword = keystoreProperties["storePassword"] as? String
		}
	}

	buildTypes {
		release {
			signingConfig = signingConfigs.getByName("release")
			isDebuggable = false
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro"
			)
		}
		debug {
			applicationIdSuffix = ".debug"
			isDebuggable = true
		}
	}
	compileOptions {
		isCoreLibraryDesugaringEnabled = true
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
	kotlinOptions {
		jvmTarget = "17"
	}
	buildFeatures {
		compose = true
		buildConfig = true
		viewBinding = true
	}
	packaging {
		resources {
			excludes += "/META-INF/{AL2.0,LGPL2.1}"
		}
	}
}

dependencies {
	implementation(libs.core.ktx)
	implementation(libs.lifecycle.runtime.ktx)
	implementation(libs.lifecycle.service)
	implementation(libs.activity.compose)
	implementation(libs.activity.ktx)
	implementation(platform(libs.compose.bom))
	implementation(libs.compose.ui)
	implementation(libs.compose.ui.graphics)
	implementation(libs.compose.ui.tooling.preview)
	implementation(libs.material3)
	implementation(libs.material.icons.extended)
	implementation(libs.appcompat)
	implementation(libs.documentfile)

	testImplementation(libs.junit)
	androidTestImplementation(libs.androidx.junit)
	androidTestImplementation(libs.espresso.core)
	androidTestImplementation(platform(libs.compose.bom))
	androidTestImplementation(libs.compose.ui.test.junit4)
	debugImplementation(libs.compose.ui.tooling)
	debugImplementation(libs.compose.ui.test.manifest)

	implementation(libs.navigation.compose)

	implementation(libs.hilt.android)
	annotationProcessor(libs.hilt.compiler)
	implementation(libs.hilt.navigation.compose)

	coreLibraryDesugaring(libs.desugar.jdk.libs)

	implementation(files("libs/ffmpeg-kit-full-gpl-6.0-2.LTS.aar"))
	implementation(libs.smart.exception)

	implementation(libs.datastore.preferences)

	implementation(libs.serialization.json)

	implementation(libs.sheets.core)
	implementation(libs.sheets.duration)
	implementation(libs.sheets.list)
	implementation(libs.sheets.input)

	implementation(libs.camerax.core)
	implementation(libs.camerax.camera2)
	implementation(libs.camerax.lifecycle)
	implementation(libs.camerax.video)
	implementation(libs.camerax.view)
	implementation(libs.camerax.extensions)

	implementation(libs.shimmer)

	implementation(libs.biometric.ktx)
}
