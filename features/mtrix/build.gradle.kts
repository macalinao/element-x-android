/*
 * mtrix fork: native rendering of `com.ianm.mtrix` rich-message payloads.
 * See README.mtrix.md at the repository root.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

plugins {
    id("io.element.android-compose-library")
}

android {
    namespace = "io.element.android.features.mtrix"
}

dependencies {
    implementation(projects.libraries.designsystem)
    implementation(projects.libraries.matrix.api)
    implementation(libs.serialization.json)

    testImplementation(libs.test.junit)
    testImplementation(libs.test.truth)
}
