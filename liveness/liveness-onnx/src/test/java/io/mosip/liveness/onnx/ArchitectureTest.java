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
                "javax.imageio..", "javax.swing..", "java.net..", "java.sql..", "java.nio.file..")
                .check(main);
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
