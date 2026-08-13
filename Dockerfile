FROM --platform=$BUILDPLATFORM maven:3.9.12-eclipse-temurin-25@sha256:4f82a03a7d6679281952d628131299b1be88d7030a49c6a2b7d2ba2642e44e3e AS build
WORKDIR /workspace
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN ./mvnw -B -ntp dependency:go-offline
COPY src ./src
RUN ./mvnw -B -ntp -DskipTests clean package

FROM eclipse-temurin:25.0.3_9-jre-ubi10-minimal@sha256:35f47084a4c1e34636fc8842780d5ca1e85b1b74de139723d1a541137932ddf2
WORKDIR /app
COPY --from=build --chown=1000:1000 /workspace/target/payment-gateway-service-0.0.1-SNAPSHOT.jar app.jar
USER 1000:1000
EXPOSE 8098
ENTRYPOINT ["java","-jar","/app/app.jar"]
