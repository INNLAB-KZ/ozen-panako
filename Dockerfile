# Build stage
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /build
COPY . .
RUN chmod +x gradlew && ./gradlew shadowJar --no-daemon

# Runtime stage
FROM eclipse-temurin:17-jre-jammy

RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg liblmdb0 && \
    rm -rf /var/lib/apt/lists/*

COPY --from=builder /build/build/libs/panako-*-all.jar /app/panako.jar

RUN mkdir -p /root/.panako/dbs

EXPOSE 8080

ENTRYPOINT ["java", "--add-opens=java.base/java.nio=ALL-UNNAMED", "-cp", "/app/panako.jar", "be.panako.http.PanakoHttpServer"]
CMD ["SERVER_PORT=8080", "STRATEGY=PANAKO"]

# Multi-platform (amd64 + arm64):
#   docker buildx build --platform linux/amd64,linux/arm64 -t innlabkz/ozen-panako:latest --push .

# docker build -t innlabkz/ozen-panako:latest .
# docker push innlabkz/ozen-panako:latest
