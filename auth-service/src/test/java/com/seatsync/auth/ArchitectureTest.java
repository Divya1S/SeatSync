package com.seatsync.auth;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.transaction.annotation.Transactional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Structural rules per CONVENTIONS §8.1. Violations are fixed by refactoring
 * the production code, never by weakening these rules.
 */
@AnalyzeClasses(packages = "com.seatsync.auth", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** Rule 1: controllers/DTOs must go through the service layer, never repositories. */
    @ArchTest
    static final ArchRule apiMustNotAccessRepositories =
            noClasses().that().resideInAnyPackage("..api..", "..web..")
                    .should().accessClassesThat().resideInAnyPackage("..repo..", "..repository..")
                    .because("the web edge must reach persistence only through the service layer (§8.1 rule 1)");

    /** Rule 2: only DTOs at the edge — no JPA entities in or out of the api package. */
    @ArchTest
    static final ArchRule apiMustNotDependOnJpaEntities =
            noClasses().that().resideInAnyPackage("..api..", "..web..")
                    .should().dependOnClassesThat().areAnnotatedWith(Entity.class)
                    .because("the web edge exchanges DTOs only; entity mapping belongs to services (§8.1 rule 2)");

    /** Rule 3: Spring silently ignores @Transactional on private methods. */
    @ArchTest
    static final ArchRule noTransactionalOnPrivateMethods =
            noMethods().that().arePrivate()
                    .should().beAnnotatedWith(Transactional.class)
                    .because("Spring proxies cannot intercept private methods, so the annotation is a no-op (§8.1 rule 3)");

    /** Rule 4: services communicate over the network only, never via each other's code. */
    @ArchTest
    static final ArchRule mustNotImportOtherSeatSyncServices =
            noClasses().should().dependOnClassesThat().resideInAnyPackage(
                            "com.seatsync.booking..",
                            "com.seatsync.catalog..",
                            "com.seatsync.notification..",
                            "com.seatsync.gateway..",
                            "com.seatsync.concierge..")
                    .because("auth-service must not import another SeatSync service's packages (§8.1 rule 4)");
}
