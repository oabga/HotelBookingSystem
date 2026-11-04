# Dockerfile dùng chung cho các service Spring Boot. Chọn service bằng build arg, ví dụ:
#   docker build --build-arg SERVICE=booking-service -t booking-service .
# Không cần cài JDK trên máy: Gradle và JDK 21 đều nằm trong bước build.

FROM eclipse-temurin:21-jdk AS build
ARG SERVICE
WORKDIR /workspace
COPY . .
# Gọi qua sh vì checkout trên Windows có thể làm mất quyền thực thi của gradlew.
# Cache ~/.gradle để lần build sau và các service sau không phải tải lại dependency.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    sh ./gradlew --no-daemon --console=plain ":${SERVICE}:bootJar" \
    && cp "${SERVICE}"/build/libs/*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home app
USER app
COPY --from=build /app.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
