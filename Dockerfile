# Builds and runs First Choice anywhere Docker runs (Render, Railway, Fly.io, a VPS ...)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY lib ./lib
COPY src ./src
RUN mkdir out && javac -cp "lib/*" -sourcepath src -d out src/com/firstchoice/busreservation/web/WebApp.java

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/out ./out
COPY lib ./lib
# PostgreSQL driver, used when DATABASE_URL is set (recommended for hosting)
ADD https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar ./lib/postgresql.jar
COPY web ./web
COPY sql ./sql
ENV PORT=8080 TRUST_PROXY=true
EXPOSE 8080
CMD ["java", "-cp", "out:lib/*", "com.firstchoice.busreservation.web.WebApp"]
