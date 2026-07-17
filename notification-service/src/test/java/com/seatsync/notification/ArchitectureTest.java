package com.seatsync.notification;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * §8.1 structural rules (CONVENTIONS.md). Violations are fixed by
 * refactoring the production code, never by weakening a rule.
 */
@AnalyzeClasses(packages = "com.seatsync.notification",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * Rule 1: the API edge must not touch persistence. Targets the repository
     * TYPE (any subtype of Spring Data's {@code Repository}) rather than a
     * package name — this service keeps its JPA repository in {@code ..domain..},
     * so a package-based rule would silently miss it.
     */
    @ArchTest
    static final ArchRule apiMustNotAccessRepositories =
            noClasses().that().resideInAnyPackage("..api..", "..web..")
                    .should().dependOnClassesThat()
                    .areAssignableTo(org.springframework.data.repository.Repository.class)
                    .because("controllers must go through a service, not read repositories directly"
                            + " (§8.1 rule 1)");

    /** Rule 2: only DTOs at the edge — no {@code @Entity} types in the API layer. */
    @ArchTest
    static final ArchRule apiMustNotDependOnEntities =
            noClasses().that().resideInAnyPackage("..api..", "..web..")
                    .should().dependOnClassesThat()
                    .areAnnotatedWith(jakarta.persistence.Entity.class)
                    .because("the API layer exposes DTOs only, never JPA entities (§8.1 rule 2)");

    /** Rule 3: Spring silently ignores {@code @Transactional} on private methods. */
    @ArchTest
    static final ArchRule noPrivateTransactionalMethods =
            methods().that()
                    .areAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
                    .or().areAnnotatedWith(jakarta.transaction.Transactional.class)
                    .should().notBePrivate()
                    .because("Spring proxies cannot intercept private methods, so @Transactional"
                            + " would be silently ignored (§8.1 rule 3)")
                    .allowEmptyShould(true);

    /** Rule 4: services stay decoupled — no imports of other SeatSync services' packages. */
    @ArchTest
    static final ArchRule noDependenciesOnOtherSeatSyncServices =
            noClasses().should().dependOnClassesThat().resideInAnyPackage(
                            "com.seatsync.auth..",
                            "com.seatsync.catalog..",
                            "com.seatsync.booking..",
                            "com.seatsync.gateway..",
                            "com.seatsync.concierge..")
                    .because("services communicate over REST/Kafka contracts, never via"
                            + " each other's code (§8.1 rule 4)");
}
