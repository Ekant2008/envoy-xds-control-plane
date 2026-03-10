FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/xds-control-plane-1.0.0.jar /app/xds-control-plane.jar
EXPOSE 18000 18001
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/xds-control-plane.jar"]
