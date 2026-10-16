package com.github.hurdadd.adapter.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.github", importOptions = ImportOption.DoNotIncludeTests.class)
class LayerDependencyTest {

    @ArchTest
    static final ArchRule domain_depends_on_nothing_above_it = noClasses().that().resideInAPackage("com.github.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("com.github.hurdadd..");

    @ArchTest
    static final ArchRule application_does_not_depend_on_adapter_or_bootstrap = noClasses()
            .that().resideInAPackage("com.github.hurdadd.application..")
            .should().dependOnClassesThat().resideInAnyPackage("com.github.hurdadd.adapter..", "com.github.hurdadd");

    @ArchTest
    static final ArchRule application_is_framework_free = noClasses()
            .that().resideInAPackage("com.github.hurdadd.application..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "tools.jackson..");
}
