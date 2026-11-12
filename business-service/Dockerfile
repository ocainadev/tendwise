# Build stage: compile the jar with the Maven wrapper.
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /app

# Download dependencies first, so they are cached while only the source changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline

COPY src src
# Tests run in CI (they need Docker for Testcontainers), not in the image build.
RUN ./mvnw -B -q package -DskipTests

# Runtime stage: only the JRE and the jar, running as a non-root user.
FROM eclipse-temurin:21-jre-noble
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /app/target/business-service-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
