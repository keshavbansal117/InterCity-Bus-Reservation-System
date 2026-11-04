# Build and run First Choice on any Docker host (Render, Railway, Fly.io, a VPS ...)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY src ./src
RUN mkdir out && javac -sourcepath src -d out src/com/firstchoice/busreservation/web/WebApp.java

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/out ./out
COPY web ./web
ENV PORT=8080
EXPOSE 8080
CMD ["java", "-cp", "out", "com.firstchoice.busreservation.web.WebApp"]
