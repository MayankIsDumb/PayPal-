# Build stage using JDK 21 Alpine
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Copy gradle wrapper and build files
COPY gradlew gradlew.bat settings.gradle build.gradle gradle.properties ./
COPY gradle/ gradle/

# Cache dependencies
RUN chmod +x gradlew && ./gradlew --version --no-daemon

# Copy source code and demo materials
COPY src/ src/
COPY demo/ demo/
COPY demo-assets/ demo-assets/

# Build application distribution
RUN ./gradlew installDist --no-daemon -x test

# Runtime stage using lightweight JRE 21
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Install fontconfig and fonts for OpenPDF invoice generation
RUN apk add --no-cache fontconfig ttf-dejavu

# Copy distribution from builder
COPY --from=builder /workspace/build/install/commishguard ./
COPY --from=builder /workspace/demo ./demo
COPY --from=builder /workspace/demo-assets ./demo-assets

# Create storage directory for JSON ledger
RUN mkdir -p storage

# Default port
ENV PORT=7070
EXPOSE 7070

# Launch server
ENTRYPOINT ["bin/commishguard"]
