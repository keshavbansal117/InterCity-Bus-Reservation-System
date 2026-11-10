package com.firstchoice.busreservation.pay;

import java.util.Map;

/** A payment provider. Two implementations: DemoGateway (no money) and RazorpayGateway (real payments). */
public interface PaymentGateway {
    String name();

    /** Creates an order for the amount (in paise). Returns the provider order id (and key for the browser). */
    Map<String, Object> createOrder(long amountPaise, String receipt) throws Exception;

    /** Verifies what the browser sent back after payment. Returns the provider payment id, or throws if invalid. */
    String verify(String orderId, Map<String, Object> clientPayload) throws Exception;

    /** Refunds part or all of a payment. Returns a provider refund reference. */
    String refund(String providerPaymentId, long amountPaise) throws Exception;
}
