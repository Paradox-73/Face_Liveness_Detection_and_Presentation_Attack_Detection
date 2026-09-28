package io.mosip.liveness.core;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.GeneralCodingRules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * T-CORE-API-02: the core must run unchanged on the desktop client and on Android API 28, and must stay free of
 * runtime dependencies.
 */
class ArchitectureTest {

    private static JavaClasses core;

    @BeforeAll
    static void importCore() {
        core = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.mosip.liveness.core");
    }

    @Test
    void noPlatformSpecificApis() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("javafx..", "android..", "androidx..", "java.awt..",
                "javax.imageio..", "javax.swing..", "java.net..", "java.sql..", "java.nio.file..")
                .check(core);
    }

    @Test
    void onlyJavaLibraryDependencies() {
        noClasses().should().dependOnClassesThat().resideOutsideOfPackages("io.mosip.liveness.core..", "java..")
                .check(core);
    }

    @Test
    void subPackagesHaveNoCycles() {
        slices().matching("io.mosip.liveness.core.(*)..").should().beFreeOfCycles().check(core);
    }

    /** The engine, stream and policy implementations must obey the same platform rules as the API. */
    @Test
    void implementationPackagesArePlatformNeutral() {
        noClasses().that().resideInAnyPackage("..core.engine..", "..core.stream..", "..core.policy..")
                .should().dependOnClassesThat().resideInAnyPackage("javafx..", "android..", "androidx..", "java.awt..",
                        "javax.imageio..", "java.net..")
                .check(core);
    }

    /** The contract packages never depend on an implementation, so a vendor engine can replace it. */
    @Test
    void contractPackagesDoNotDependOnImplementations() {
        noClasses().that().resideInAnyPackage("io.mosip.liveness.core", "..core.api..", "..core.config..",
                "..core.audit..", "..core.ui..", "..core.vision..", "..core.device..", "..core.image..")
                .should().dependOnClassesThat().resideInAnyPackage("..core.engine..", "..core.stream..", "..core.policy..")
                .check(core);
    }

    /**
     * Only the documented entry points of the engine are public; the algorithms, in particular the challenge
     * scheduler, stay package-private so no caller can choose a challenge (T-CORE-CH-01).
     */
    @Test
    void engineAlgorithmsArePackagePrivate() {
        classes().that().resideInAPackage("..core.engine").and().arePublic().and().areTopLevelClasses()
                .should().haveSimpleNameStartingWith("DefaultLivenessEngine")
                .orShould().haveSimpleNameStartingWith("EngineParameter")
                .orShould().haveSimpleName("LivenessTransaction")
                .orShould().haveSimpleName("LivenessUiStateMapper")
                .orShould().haveSimpleName("AuditPayloadBuilder")
                .orShould().haveSimpleName("LivenessMetrics")
                .orShould().haveSimpleName("DiagnosticLog")
                .check(core);
    }

    /** Biometric data must not leak through console output or a logger the clients do not control. */
    @Test
    void noConsoleOrJavaUtilLogging() {
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(core);
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(core);
    }
}
