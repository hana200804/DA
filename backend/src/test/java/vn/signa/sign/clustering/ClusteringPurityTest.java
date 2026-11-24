package vn.signa.sign.clustering;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Lõi gom cụm là Java thuần: không Spring, không JDBC, không I/O. */
class ClusteringPurityTest {

  @Test
  void clusteringCoreIsPureJava() {
    noClasses()
        .that()
        .resideInAPackage("vn.signa.sign.clustering..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "java.sql..",
            "javax.sql..",
            "java.io..",
            "java.nio..",
            "java.net..",
            "vn.signa.shared..")
        .because("docs/backend/thiet-ke-trien-khai.md §5.2: logic gom cụm là Java thuần")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.signa.sign.clustering"));
  }
}
