# One Dockerfile for both services: docker build --build-arg MODULE=payment-orchestrator .
FROM eclipse-temurin:27-jdk AS build
ARG MODULE
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon :${MODULE}:bootstrap:bootJar -x test \
 && cp ${MODULE}/bootstrap/build/libs/*.jar /app.jar

FROM eclipse-temurin:27-jre
COPY --from=build /app.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
