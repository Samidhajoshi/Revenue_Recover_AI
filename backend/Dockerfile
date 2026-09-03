# --- Build stage --------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Cache dependencies separately from source so a source-only change doesn't
# re-download the whole repository on every build.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# --- Run stage ------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# pom.xml sets <finalName>recoverai-backend</finalName>, so the packaged
# jar has no version suffix - keep this in sync if that ever changes.
COPY --from=build /app/target/recoverai-backend.jar app.jar

# Render (and most PaaS) injects PORT at runtime; application.yml already
# reads it via ${PORT:8082}. EXPOSE is documentation only, not enforced.
EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
