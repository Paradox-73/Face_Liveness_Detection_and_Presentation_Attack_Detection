/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.facematch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The SDK must run on the desktop and on Android: MOSIP types only at the {@code IBioApiV2} edge, desktop image
 * classes only in the ImageIO decoder package, and no ONNX Runtime classes outside {@code liveness-onnx}.
 */
class ArchitectureTest {

    private static JavaClasses main;

    @BeforeAll
    static void importMain() {
        main = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.mosip.liveness.facematch");
    }

    @Test
    void onlyTheImageIoPackageUsesDesktopImageClasses() {
        noClasses().that().resideOutsideOfPackage("io.mosip.liveness.facematch.imageio..").should()
                .dependOnClassesThat().resideInAnyPackage("java.awt..", "javax.imageio..").check(main);
    }

    @Test
    void noPlatformUiNetworkOrFileApis() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("javafx..", "android..", "androidx..",
                "javax.swing..", "java.net..", "java.sql..", "java.nio.file..", "ai.onnxruntime..").check(main);
    }

    @Test
    void mosipTypesStayAtTheSdkEdge() {
        classes().that().resideInAnyPackage("io.mosip.liveness.facematch.iso..", "io.mosip.liveness.facematch.imageio..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "javax.imageio..",
                        "io.mosip.liveness..").check(main);
        noClasses().that().haveSimpleNameNotStartingWith("SFaceBioApi").and().doNotHaveSimpleName("Birs")
                .should().dependOnClassesThat().resideInAPackage("io.mosip.kernel..").check(main);
    }

    @Test
    void noLoggingOfBiometricData() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("org.slf4j..", "java.util.logging..",
                "io.mosip.kernel.core.logger..").check(main);
    }
}
