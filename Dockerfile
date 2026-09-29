FROM maven:3.8.6-openjdk-11-slim AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM ubuntu:22.04
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openjdk-11-jre-headless \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/heat-network-router-0.1.0-SNAPSHOT.jar app.jar
COPY models ./models
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
