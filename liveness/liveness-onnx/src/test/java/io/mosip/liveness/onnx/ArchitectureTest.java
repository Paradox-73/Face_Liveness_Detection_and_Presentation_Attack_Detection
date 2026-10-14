/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The adapters must run unchanged on the desktop client and on Android: no platform image or UI classes, only
 * the JDK, the liveness core and ONNX Runtime.
 */
class ArchitectureTest {

    private static JavaClasses main;

    @BeforeAll
    static void importMain() {
        main = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.mosip.liveness.onnx");
    }

    @Test
    void noPlatformSpecificApis() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("javafx..", "android..", "androidx..", "java.awt..",
                "javax.imageio..", "javax.swing..", "java.sql..")
                .check(main);
    }

    /** The engine never waits for a server: only the shared model update code may download a bundle. */
    @Test
    void onlyTheBundlePackageUsesTheNetwork() {
        noClasses().that().resideOutsideOfPackage("io.mosip.liveness.onnx.bundle..").should().dependOnClassesThat()
                .resideInAPackage("java.net..").check(main);
    }

    /** Model bundles come from a local folder or a download; only that shared code may touch the file system. */
    @Test
    void onlyTheBundlePackageUsesTheFileSystem() {
        noClasses().that().resideOutsideOfPackage("io.mosip.liveness.onnx.bundle..").should().dependOnClassesThat()
                .resideInAPackage("java.nio.file..").check(main);
    }

    @Test
    void onlyJdkCoreAndOnnxRuntime() {
        noClasses().should().dependOnClassesThat().resideOutsideOfPackages("io.mosip.liveness..", "java..",
                "ai.onnxruntime..").check(main);
    }

    @Test
    void onlyTheRuntimePackageTouchesOnnxRuntime() {
        noClasses().that().resideOutsideOfPackage("io.mosip.liveness.onnx.runtime..").should().dependOnClassesThat()
                .resideInAPackage("ai.onnxruntime..").check(main);
    }

    @Test
    void subPackagesHaveNoCycles() {
        slices().matching("io.mosip.liveness.onnx.(*)..").should().beFreeOfCycles().check(main);
    }
}
