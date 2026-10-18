package com.bank.accounts.domain;

import com.bank.shared.kernel.domain.ValueObject;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;

/** The Money rule (EA docs/architecture/MONEY_ARCHUNIT_RULE_PROPOSAL.md), adopted from day one. */
class AccountsMoneyArchitectureTest {

    private static final JavaClasses DOMAIN = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.bank.accounts.domain");

    @Test
    void domainHoldsNoFloatingPointFields() {
        fields().that().areDeclaredInClassesThat().resideInAPackage("com.bank.accounts.domain..")
            .should().notHaveRawType(double.class)
            .andShould().notHaveRawType(Double.class)
            .andShould().notHaveRawType(float.class)
            .andShould().notHaveRawType(Float.class)
            .because("binary floating point cannot hold money exactly")
            .check(DOMAIN);
    }

    @Test
    void onlyValueObjectsHoldBigDecimalFields() {
        fields().that().areDeclaredInClassesThat().resideInAPackage("com.bank.accounts.domain..")
            .and().areDeclaredInClassesThat().doNotImplement(ValueObject.class)
            .should().notHaveRawType(BigDecimal.class)
            .because("an amount in an aggregate, entity or event is Money, which carries currency and scale")
            .check(DOMAIN);
    }
}
