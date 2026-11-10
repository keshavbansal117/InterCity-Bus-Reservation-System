package com.firstchoice.busreservation.pay;

import com.firstchoice.busreservation.util.Security;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pretends to take payment. The browser sends outcome "success" or "fail". No money moves. */
public final class DemoGateway implements PaymentGateway {
    @Override public String name() { return "DEMO"; }

    @Override public Map<String, Object> createOrder(long amountPaise, String receipt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", "demo_" + Security.randomToken(6));
        m.put("demo", true);
        return m;
    }

    @Override public String verify(String orderId, Map<String, Object> payload) {
        if (!"success".equals(String.valueOf(payload.get("outcome")))) throw new IllegalStateException("Payment was declined.");
        return "demo_pay_" + Security.randomToken(6);
    }

    @Override public String refund(String providerPaymentId, long amountPaise) { return "demo_refund_" + Security.randomToken(6); }
}
