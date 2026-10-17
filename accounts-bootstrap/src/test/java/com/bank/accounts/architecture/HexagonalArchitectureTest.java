package com.bank.accounts.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The four service guardrail rules (FINTECHBANKX_SERVICE_GUARDRAILS, ADR-028/029),
 * checked over the production classes of every module on `check`.
 */
class HexagonalArchitectureTest {

    private static final String DOMAIN = "com.bank.accounts.domain..";
    private static final String PORT_IN = "com.bank.accounts.domain.port.in..";
    private static final String PORT_OUT = "com.bank.accounts.domain.port.out..";
    private static final String APPLICATION = "com.bank.accounts.application..";
    private static final String INFRASTRUCTURE = "com.bank.accounts.infrastructure..";

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.bank.accounts");
    }

    @Test
    void rule1DomainIsFreeOfOuterLayersAndFrameworks() {
        noClasses().that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(APPLICATION, INFRASTRUCTURE,
                "org.springframework..", "jakarta.persistence..", "org.hibernate..", "org.apache.kafka..",
                "com.mongodb..", "org.bson..", "com.fasterxml.jackson..")
            .check(production);
    }

    @Test
    void rule2ApplicationDoesNotDependOnInfrastructure() {
        noClasses().that().resideInAPackage(APPLICATION)
            .should().dependOnClassesThat().resideInAPackage(INFRASTRUCTURE)
            .check(production);
    }

    @Test
    void rule3DrivingAdaptersUseInPortsNotApplicationClasses() {
        noClasses().that().areAnnotatedWith(RestController.class)
            .or().areAnnotatedWith(Controller.class)
            .or(declareKafkaListeners())
            .should().dependOnClassesThat().resideInAPackage(APPLICATION)
            .check(production);
        classes().that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().resideInAPackage(PORT_IN)
            .check(production);
    }

    @Test
    void rule4OutPortImplementationsLiveInInfrastructure() {
        classes().that().implement(resideInOutPorts())
            .and().areNotInterfaces()
            .should().resideInAPackage(INFRASTRUCTURE)
            .check(production);
    }

    private static DescribedPredicate<JavaClass> declareKafkaListeners() {
        return DescribedPredicate.describe("declare @KafkaListener methods",
            javaClass -> javaClass.isAnnotatedWith(KafkaListener.class)
                || javaClass.getMethods().stream().anyMatch(m -> m.isAnnotatedWith(KafkaListener.class)));
    }

    private static DescribedPredicate<JavaClass> resideInOutPorts() {
        return DescribedPredicate.describe("an interface in domain.port.out",
            javaClass -> javaClass.isInterface() && javaClass.getPackageName().startsWith("com.bank.accounts.domain.port.out"));
    }
}
