FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY infra/maven-settings.xml /src/settings.xml
COPY backend/pom.xml ./pom.xml
COPY backend/src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -s /src/settings.xml -B -DskipTests package
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -g 10001 serviceflow && adduser -D -u 10001 -G serviceflow serviceflow
WORKDIR /app
COPY --from=build /src/target/serviceflow-api-0.1.0.jar app.jar
USER serviceflow
EXPOSE 18081
ENTRYPOINT ["java","-jar","app.jar"]
