package com.seatsync.concierge.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.transaction.annotation.Transactional;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Structural rules from CONVENTIONS.md §8.1. This service has no JPA
 * repositories or entities today; the rules stay in place so a violation
 * introduced later fails immediately instead of silently becoming the norm.
 * Violations are fixed by refactoring, never by weakening a rule.
 */
@AnalyzeClasses(packages = "com.seatsync.concierge", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** Rule 1: the web edge must not reach into persistence classes. */
    @ArchTest
    static final ArchRule apiMustNotAccessRepositories = noClasses()
            .that().resideInAnyPackage("..api..", "..web..")
            .should().dependOnClassesThat().resideInAnyPackage("..repo..", "..repository..")
            .because("controllers and web classes must go through services, not repositories (§8.1 rule 1)");

    /** Rule 2: DTOs only at the edge — no @Entity types in api/web (by name: no JPA on this classpath). */
    @ArchTest
    static final ArchRule apiMustNotDependOnEntities = noClasses()
            .that().resideInAnyPackage("..api..", "..web..")
            .should().dependOnClassesThat().areAnnotatedWith("jakarta.persistence.Entity")
            .because("the web edge exposes DTOs, never JPA entities (§8.1 rule 2)");

    /** Rule 3: Spring silently ignores @Transactional on private methods. */
    @ArchTest
    static final ArchRule noTransactionalOnPrivateMethods = methods()
            .that().areAnnotatedWith(Transactional.class)
            .or().areAnnotatedWith("jakarta.transaction.Transactional")
            .should().notBePrivate()
            .because("Spring silently ignores @Transactional on private methods (§8.1 rule 3)")
            // this service currently declares no @Transactional methods; the rule must still exist and pass
            .allowEmptyShould(true);

    /** Rule 4: no compile-time coupling to any other SeatSync service's packages. */
    @ArchTest
    static final ArchRule noImportsOfOtherSeatsyncServices = noClasses()
            .should().dependOnClassesThat(
                    resideInAPackage("com.seatsync..")
                            .and(DescribedPredicate.not(resideInAPackage("com.seatsync.concierge..")))
                            .as("reside in another SeatSync service's packages (com.seatsync.* outside com.seatsync.concierge)"))
            .because("services integrate over HTTP/Kafka contracts, never by importing each other's code (§8.1 rule 4)");
}
