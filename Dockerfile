FROM maven:3.8.6-openjdk-11-slim AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:11-jre
WORKDIR /app
COPY --from=build /app/target/heat-network-router-0.1.0-SNAPSHOT.jar app.jar
COPY models ./models
COPY data ./data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
