# Package the exact JAR produced by the preceding Java 11 Maven test stage.
FROM ubuntu:22.04
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openjdk-11-jre-headless \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY target/heat-network-router-0.1.0-SNAPSHOT.jar /app/app.jar
COPY models /app/models
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
