FROM gradle:8.10.2-jdk21 AS build
WORKDIR /app
COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies
COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/wallet-backend.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
