FROM gradle:8.14-jdk21 AS build

WORKDIR /home/gradle/src

COPY build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src

RUN gradle --no-daemon clean bootJar

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /home/gradle/src/build/libs/*.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
