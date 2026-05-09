FROM eclipse-temurin:17-jre

RUN apt-get update && apt-get install -y ffmpeg && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY target/smart-camera-1.0.0.jar app.jar

EXPOSE 8080 8554

ENTRYPOINT ["java", "-jar", "app.jar"]
