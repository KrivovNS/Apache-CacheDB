# Этап 1: сборка проекта с Maven
FROM maven:3.9.9-eclipse-temurin-21 AS builder

# Рабочая папка внутри контейнера
WORKDIR /build

# Копируем pom.xml и качаем зависимости
COPY pom.xml .
RUN mvn -B -e -ntp dependency:go-offline

# Копируем исходники и собираем JAR
COPY src ./src
RUN mvn -B -e -ntp clean package -DskipTests

# Этап 2: минимальный образ для запуска
FROM eclipse-temurin:21-jre

WORKDIR /app

# Копируем собранный JAR из первого этапа
COPY --from=builder /build/target/Apache-CacheDB-1.0-SNAPSHOT-shaded.jar app.jar

# Копируем ресурсы (application.properties и прочее)
COPY src/main/resources ./resources

# Создаём папку для H2 базы (соответствует db.url=jdbc:h2:file:./data/storagedb)
RUN mkdir -p /app/data
VOLUME /app/data

# Открываем порт приложения
EXPOSE 8080
EXPOSE 9090
# Запуск приложения
ENTRYPOINT ["java", "-jar", "app.jar"]
