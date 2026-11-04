import com.firstchoice.busreservation.web.Json;
import com.firstchoice.busreservation.web.WebApp;
import com.sun.net.httpserver.HttpServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

public class WebIntegrationTest {
    static int pass = 0, fail = 0;
    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (ok) pass++; else fail++;
    }

    public static void main(String[] args) throws Exception {
        WebApp app = new WebApp(Path.of("web").toAbsolutePath().normalize());
        HttpServer server = app.startServer(0); // bind to an ephemeral port
        int port = server.getAddress().getPort();
        String base = "http://localhost:" + port;
        HttpClient client = HttpClient.newHttpClient();

        try {
            // 1. Static file serving & security headers
            HttpResponse<String> homeRes = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            check("GET / returns 200", homeRes.statusCode() == 200);
            check("GET / contains First Choice title", homeRes.body().contains("First Choice"));
            check("Static response has nosniff header", "nosniff".equals(homeRes.headers().firstValue("X-Content-Type-Options").orElse("")));

            // 2. OPTIONS preflight CORS
            HttpResponse<Void> optRes = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/search")).method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding()
            );
            check("OPTIONS /api/search returns 204", optRes.statusCode() == 204);
            check("OPTIONS returns Allow header", optRes.headers().firstValue("Allow").orElse("").contains("GET"));

            // 3. GET /api/cities
            HttpResponse<String> citiesRes = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/cities")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            check("GET /api/cities returns 200", citiesRes.statusCode() == 200);
            check("Cities list contains Barnala & Chandigarh", citiesRes.body().contains("Barnala") && citiesRes.body().contains("Chandigarh"));

            // 4. POST /api/login (valid)
            String loginBody = Json.stringify(Map.of("email", "user@demo.com", "password", "user123"));
            HttpResponse<String> loginRes = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/login"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(loginBody))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            check("POST /api/login returns 200", loginRes.statusCode() == 200);
            Map<String, Object> loginData = Json.parseObject(loginRes.body());
            String token = (String) loginData.get("token");
            check("Login returns session token", token != null && !token.isBlank());

            // 5. GET /api/me with Bearer token
            HttpResponse<String> meRes = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/me"))
                            .header("Authorization", "Bearer " + token)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            check("GET /api/me returns 200 with valid token", meRes.statusCode() == 200);
            Map<String, Object> meData = Json.parseObject(meRes.body());
            check("GET /api/me returns correct email", "user@demo.com".equals(meData.get("email")));

            // 6. GET /api/search
            LocalDate tomorrow = LocalDate.now().plusDays(1);
            HttpResponse<String> searchRes = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/search?from=Barnala&to=Chandigarh&date=" + tomorrow)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            check("GET /api/search returns 200", searchRes.statusCode() == 200);
            check("Search returns schedules", searchRes.body().contains("Punjab Express") || searchRes.body().contains("Malwa Travels"));

            // 7. POST /api/logout
            HttpResponse<String> logoutRes = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/logout"))
                            .header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            check("POST /api/logout returns 200", logoutRes.statusCode() == 200);

            // 8. GET /api/me after logout -> 401
            HttpResponse<String> meAfterLogout = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/me"))
                            .header("Authorization", "Bearer " + token)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            check("GET /api/me after logout returns 401 Unauthorized", meAfterLogout.statusCode() == 401);

            // 9. Root /api returns 404 safely without 500 error
            HttpResponse<String> apiRootRes = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            check("GET /api returns 404 (handled gracefully)", apiRootRes.statusCode() == 404);

        } finally {
            server.stop(0);
        }

        System.out.println("\nWeb Integration Test: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
