package com.firstchoice.busreservation.pay;

import com.firstchoice.busreservation.util.Security;
import com.firstchoice.busreservation.web.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Real payments through Razorpay (https://razorpay.com). Flow:
 * 1. server creates an order  2. browser opens Razorpay Checkout and the customer pays
 * 3. browser sends payment id + signature  4. server verifies the signature (HMAC-SHA256) before confirming.
 * The secret key never leaves the server.
 */
public final class RazorpayGateway implements PaymentGateway {
    private final String keyId, keySecret;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public RazorpayGateway(String keyId, String keySecret) { this.keyId = keyId; this.keySecret = keySecret; }

    @Override public String name() { return "RAZORPAY"; }

    private String auth() {
        return "Basic " + Base64.getEncoder().encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> post(String path, Map<String, Object> body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.razorpay.com/v1" + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", auth()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body))).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> json = Json.parseObject(res.body());
        if (res.statusCode() >= 300) throw new IllegalStateException("Razorpay error " + res.statusCode() + ": " + json.get("error"));
        return json;
    }

    @Override public Map<String, Object> createOrder(long amountPaise, String receipt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountPaise);
        body.put("currency", "INR");
        body.put("receipt", receipt);
        Map<String, Object> order = post("/orders", body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", order.get("id"));
        out.put("keyId", keyId);
        out.put("demo", false);
        return out;
    }

    @Override public String verify(String orderId, Map<String, Object> p) {
        String paymentId = String.valueOf(p.get("razorpay_payment_id"));
        String signature = String.valueOf(p.get("razorpay_signature"));
        String expected = Security.hmacSha256Hex(keySecret, orderId + "|" + paymentId);
        if (!Security.constantTimeEquals(expected, signature)) throw new IllegalStateException("Payment signature could not be verified.");
        return paymentId;
    }

    @Override public String refund(String providerPaymentId, long amountPaise) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountPaise);
        return String.valueOf(post("/payments/" + providerPaymentId + "/refund", body).get("id"));
    }
}
