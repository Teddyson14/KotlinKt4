FROM gradle:8.2-jdk17 AS build
LABEL authors="mishg"
WORKDIR /app
COPY . .
RUN gradle clean shadowJar --no-daemon

FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
