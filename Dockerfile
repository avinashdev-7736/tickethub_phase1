# One Dockerfile for every module:  docker build --build-arg MODULE_PATH=services/booking-service -t tickethub/booking-service .
FROM maven:3.9-eclipse-temurin-17 AS build
ARG MODULE_PATH
WORKDIR /src
COPY pom.xml .
COPY common common
COPY services services
COPY platform platform
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl ${MODULE_PATH} -am -DskipTests package

FROM eclipse-temurin:17-jre-jammy
ARG MODULE_PATH
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* && useradd -r -u 1001 app
WORKDIR /app
COPY --from=build /src/${MODULE_PATH}/target/*.jar app.jar
USER app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
