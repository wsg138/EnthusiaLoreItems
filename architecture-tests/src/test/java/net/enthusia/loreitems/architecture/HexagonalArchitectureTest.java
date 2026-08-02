package net.enthusia.loreitems.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "net.enthusia.loreitems",
        importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {
    @ArchTest
    static final ArchRule DOMAIN_IS_PLATFORM_FREE =
            noClasses()
                    .that()
                    .resideInAPackage("..domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.bukkit..",
                            "io.papermc..",
                            "java.sql..",
                            "org.sqlite..",
                            "org.yaml.snakeyaml..");

    @ArchTest
    static final ArchRule APPLICATION_IS_PLATFORM_FREE =
            noClasses()
                    .that()
                    .resideInAPackage("..application..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.bukkit..",
                            "io.papermc..",
                            "java.sql..",
                            "org.sqlite..",
                            "org.yaml.snakeyaml..");

    @ArchTest
    static final ArchRule SQLITE_DOES_NOT_DEPEND_ON_PAPER =
            noClasses()
                    .that()
                    .resideInAPackage("..sqlite..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("org.bukkit..", "io.papermc..");

    @ArchTest
    static final ArchRule PACKAGE_SLICES_ARE_ACYCLIC =
            slices()
                    .matching("net.enthusia.loreitems.(*)..")
                    .should()
                    .beFreeOfCycles();
}
