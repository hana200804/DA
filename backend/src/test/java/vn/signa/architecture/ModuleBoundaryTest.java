package vn.signa.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ranh giới module theo ADR-0005: module khác chỉ được dùng gói {@code api}. */
class ModuleBoundaryTest {

  private static final JavaClasses MAIN =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("vn.signa");

  static List<ArchRule> rules(String root) {
    List<ArchRule> rules = new ArrayList<>();
    for (String module : Modules.NAMES) {
      for (String other : Modules.NAMES) {
        if (module.equals(other)) continue;
        rules.add(
            noClasses()
                .that()
                .resideInAPackage(root + "." + module + "..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                    root + "." + other + ".internal..", root + "." + other + ".web..")
                .as(module + " chỉ được dùng " + other + ".api"));
      }
    }
    rules.add(
        noClasses()
            .that()
            .resideInAPackage(root + ".shared..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                Modules.NAMES.stream().map(m -> root + "." + m + "..").toArray(String[]::new))
            .as("shared không phụ thuộc module nghiệp vụ"));
    rules.add(
        slices()
            .matching(root + ".(*)..")
            .should()
            .beFreeOfCycles()
            .as("không có chu trình giữa các module"));
    return rules;
  }

  @Test
  void mainCodeRespectsModuleBoundaries() {
    rules("vn.signa").forEach(rule -> rule.allowEmptyShould(true).check(MAIN));
  }

  @Test
  void rulesCatchAViolation() {
    JavaClasses bad =
        new ClassFileImporter().importPackages("vn.signa.architecture.fixture.violation");
    List<String> failures = new ArrayList<>();
    for (ArchRule rule : rules("vn.signa.architecture.fixture.violation")) {
      EvaluationResult result = rule.allowEmptyShould(true).evaluate(bad);
      if (result.hasViolation()) failures.add(rule.getDescription());
    }
    assertThat(failures).contains("sign chỉ được dùng identity.api");
  }
}
