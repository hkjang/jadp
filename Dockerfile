FROM eclipse-temurin:17-jre-jammy

WORKDIR /opt/jadp

RUN apt-get update \
    && apt-get install -y --no-install-recommends fontconfig fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home spring \
    && mkdir -p /var/app-data \
    && chown -R spring:spring /opt/jadp /var/app-data

ARG JAR_FILE=target/jadp-0.0.1-SNAPSHOT.jar

COPY ${JAR_FILE} app.jar

ENV JAVA_OPTS="" \
    APP_STORAGE_BASE_DIR=/var/app-data

EXPOSE 8080

USER spring

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /opt/jadp/app.jar"]
