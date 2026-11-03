# TEMPORARY image for the numbering load test.
# run.sh builds it with build/libs as the context, after ./gradlew loadtestBootJar.
FROM eclipse-temurin:21.0.8_9-jre-alpine

WORKDIR /app
COPY invoicekit-loadtest.jar app.jar

# Run as a non-root user, because the service doesn't need any special access.
USER nobody
ENV SPRING_PROFILES_ACTIVE=loadtest
EXPOSE 8081

# Lets the heap use most of the pod's memory limit instead of the JVM's default quarter.
CMD ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
