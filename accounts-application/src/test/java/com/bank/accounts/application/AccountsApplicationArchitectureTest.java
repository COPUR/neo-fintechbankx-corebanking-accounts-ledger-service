package com.bank.accounts.application;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class AccountsApplicationArchitectureTest {

    @Test
    void applicationDoesNotDependOnInfrastructure() {
        JavaClasses classes = new ClassFileImporter().importPackagesOf(AccountsLedgerService.class);

        noClasses()
            .that().resideInAPackage("com.bank.accounts.application..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.bank.accounts.infrastructure..")
            .check(classes);
    }
}
