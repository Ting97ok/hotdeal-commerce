package com.sparta.msa.commerce.domain.payment.gateway;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@Service
@RequiredArgsConstructor
public class TossPaymentClient implements PaymentGatewayClient {

  private final TossHttpClient tossHttpClient;

  @Override
  public PgConfirmResult confirm(String paymentKey, String orderId, BigDecimal amount) {
    try {
      TossConfirmResponse response = tossHttpClient.confirm(new TossConfirmRequest(paymentKey, orderId, amount));
      return new PgConfirmResult.Approved(
          response.paymentKey(),
          null,
          response.totalAmount(),
          response.approvedAt().toLocalDateTime()
      );
    } catch (HttpClientErrorException e) {
      return new PgConfirmResult.Rejected();
    } catch (HttpServerErrorException e) {
      return new PgConfirmResult.GatewayError();
    } catch (ResourceAccessException e) {
      return requestNotDelivered(e.getCause())
          ? new PgConfirmResult.GatewayError()
          : new PgConfirmResult.InDoubt();
    }
  }

  private boolean requestNotDelivered(Throwable cause) {
    return cause instanceof ConnectException
        || cause instanceof HttpConnectTimeoutException
        || cause instanceof UnknownHostException;
  }
}
