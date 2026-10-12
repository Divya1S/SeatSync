FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests
FROM eclipse-temurin:21-jre
# Run as an unprivileged user: a container escape from the JVM should not start as root.
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin seatsync
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER seatsync
EXPOSE 8084
ENTRYPOINT ["java","-jar","app.jar"]
