/*
 * mtrix fork: native rendering of `com.ianm.mtrix` rich-message payloads.
 * See README.mtrix.md at the repository root.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

import extension.testCommonDependencies

plugins {
    id("io.element.android-compose-library")
}

android {
    namespace = "io.element.android.features.mtrix"
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(projects.libraries.designsystem)
    implementation(projects.libraries.matrix.api)
    implementation(libs.serialization.json)

    testCommonDependencies(libs, includeTestComposeView = true)
}
