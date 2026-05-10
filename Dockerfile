FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -e -DskipTests dependency:go-offline
COPY . .
RUN mvn package -DskipTests

FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY --from=build /app/target/sentry-demo-1.0-SNAPSHOT.jar app.jar

ENV SENTRY_DSN=""
EXPOSE 8080

CMD ["java", "-jar", "app.jar"]