# Ảnh backend Signa. Build từ gốc repo:
#   docker compose -f infra/compose.yaml --profile app build
# Tầng 1 build bootJar và tách lớp (dependencies ít đổi nằm dưới, mã ứng dụng nằm trên cùng);
# tầng 2 chỉ có JRE và chạy bằng user không phải root.
FROM eclipse-temurin:21.0.13.1_2-jdk-noble AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# Tải Gradle và phụ thuộc trước khi chép mã, để đổi mã không phải tải lại.
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q bootJar \
 && jar="$(find build/libs -name '*.jar' ! -name '*-plain.jar')" \
 && java -Djarmode=tools -jar "$jar" extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21.0.13.1_2-jre-noble
RUN groupadd --system --gid 10001 signa \
 && useradd --system --uid 10001 --gid signa --no-create-home --shell /usr/sbin/nologin signa
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
