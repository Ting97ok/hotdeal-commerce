package com.sparta.msa.commerce.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.sparta.msa.commerce.domain.hotdeal.dto.request.CreateHotDealRequest;
import com.sparta.msa.commerce.domain.hotdeal.entity.HotDeal;
import com.sparta.msa.commerce.domain.hotdeal.repository.HotDealRepository;
import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.domain.order.entity.OrderStatus;
import com.sparta.msa.commerce.domain.order.repository.OrderRepository;
import com.sparta.msa.commerce.domain.order.service.CommonOrderService;
import com.sparta.msa.commerce.domain.payment.entity.Payment;
import com.sparta.msa.commerce.domain.payment.entity.PaymentStatus;
import com.sparta.msa.commerce.domain.payment.facade.PaymentResolutionFacade;
import com.sparta.msa.commerce.domain.payment.gateway.PaymentGatewayClient;
import com.sparta.msa.commerce.domain.payment.gateway.PgConfirmResult;
import com.sparta.msa.commerce.domain.payment.gateway.PgPayment;
import com.sparta.msa.commerce.domain.payment.gateway.PgPaymentStatus;
import com.sparta.msa.commerce.domain.payment.repository.PaymentRepository;
import com.sparta.msa.commerce.domain.product.entity.Product;
import com.sparta.msa.commerce.domain.product.repository.ProductRepository;
import com.sparta.msa.commerce.domain.stock.entity.HotDealStock;
import com.sparta.msa.commerce.domain.stock.entity.ProductStock;
import com.sparta.msa.commerce.domain.stock.repository.HotDealStockRepository;
import com.sparta.msa.commerce.domain.stock.repository.ProductStockRepository;
import com.sparta.msa.commerce.domain.stock.service.ProductStockService;
import com.sparta.msa.commerce.domain.user.entity.User;
import com.sparta.msa.commerce.domain.user.entity.UserRole;
import com.sparta.msa.commerce.domain.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("IN_DOUBT 해소")
class PaymentResolutionIntegrationTest {

  @Autowired PaymentResolutionFacade paymentResolutionFacade;
  @Autowired PaymentRepository paymentRepository;
  @Autowired OrderRepository orderRepository;
  @Autowired UserRepository userRepository;
  @Autowired ProductRepository productRepository;
  @Autowired HotDealRepository hotDealRepository;
  @Autowired ProductStockRepository productStockRepository;
  @Autowired HotDealStockRepository hotDealStockRepository;
  @Autowired ProductStockService productStockService;
  @Autowired CommonOrderService commonOrderService;
  @Autowired PasswordEncoder passwordEncoder;
  @MockBean PaymentGatewayClient paymentGatewayClient;

  @BeforeEach
  void clean() {
    paymentRepository.deleteAll();
    orderRepository.deleteAll();
    productStockRepository.deleteAll();
    hotDealStockRepository.deleteAll();
    hotDealRepository.deleteAll();
    productRepository.deleteAll();
    userRepository.deleteAll();
  }

  private Payment saveInDoubtPayment(String paymentKey) {
    User user = userRepository.save(
        User.create("buyer@test.com", passwordEncoder.encode("pass"), "구매자", UserRole.USER));
    Product product = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));
    productStockRepository.save(ProductStock.create(product.getId(), 100));
    productStockService.reserve(product.getId(), 1);
    productStockService.confirmSale(product.getId(), 1);
    LocalDateTime start = LocalDateTime.now().minusHours(1);
    LocalDateTime end = LocalDateTime.now().plusHours(1);
    HotDeal hotDeal = hotDealRepository.save(HotDeal.create(
        new CreateHotDealRequest(product.getId(), new BigDecimal("19800"), 100, 5, start, end), product));
    hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), 99));
    Order order = orderRepository.save(Order.create(user, hotDeal, product, 1, Duration.ofMinutes(10)));
    commonOrderService.markPaid(order);
    return paymentRepository.save(Payment.createInDoubt(order, paymentKey));
  }

  @Test
  @DisplayName("결제 조회가 DONE이면 IN_DOUBT을 DONE으로 확정한다")
  void resolvesToDoneWhenGatewayDone() {
    Payment payment = saveInDoubtPayment("toss_pk_x");
    given(paymentGatewayClient.getPayment("toss_pk_x"))
        .willReturn(new PgPayment(PgPaymentStatus.DONE, new BigDecimal("19800"), LocalDateTime.now()));

    paymentResolutionFacade.resolveInDoubt(LocalDateTime.now().plusMinutes(5));

    Payment resolved = paymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(resolved.getStatus()).isEqualTo(PaymentStatus.DONE);
  }

  @Test
  @DisplayName("결제 조회가 EXPIRED이면 Payment FAILED·주문 CANCELED·재고를 복원한다")
  void resolvesToFailedWhenGatewayExpired() {
    Payment payment = saveInDoubtPayment("toss_pk_y");
    given(paymentGatewayClient.getPayment("toss_pk_y"))
        .willReturn(new PgPayment(PgPaymentStatus.EXPIRED, null, null));

    paymentResolutionFacade.resolveInDoubt(LocalDateTime.now().plusMinutes(5));

    Payment resolved = paymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(resolved.getStatus()).isEqualTo(PaymentStatus.FAILED);

    Order order = orderRepository.findById(payment.getOrderId()).orElseThrow();
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);

    ProductStock stock = productStockRepository.findByProductId(order.getProductId()).orElseThrow();
    assertThat(stock.getOnHandQuantity()).isEqualTo(100);

    HotDealStock hotDealStock = hotDealStockRepository.findByHotDealId(order.getHotDealId()).orElseThrow();
    assertThat(hotDealStock.getRemainingQuantity()).isEqualTo(100);
  }

  @Test
  @DisplayName("결제 조회가 IN_PROGRESS이면 confirm 멱등 재시도가 Approved일 때 DONE으로 확정한다")
  void retriesConfirmWhenGatewayInProgress() {
    Payment payment = saveInDoubtPayment("toss_pk_p");
    Order order = orderRepository.findById(payment.getOrderId()).orElseThrow();
    given(paymentGatewayClient.getPayment("toss_pk_p"))
        .willReturn(new PgPayment(PgPaymentStatus.IN_PROGRESS, null, null));
    given(paymentGatewayClient.confirm("toss_pk_p", order.getOrderNo(), payment.getAmount()))
        .willReturn(new PgConfirmResult.Approved("toss_pk_p", "toss_pk_p", payment.getAmount(), LocalDateTime.now()));

    paymentResolutionFacade.resolveInDoubt(LocalDateTime.now().plusMinutes(5));

    Payment resolved = paymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(resolved.getStatus()).isEqualTo(PaymentStatus.DONE);
  }

  @Test
  @DisplayName("IN_PROGRESS confirm 재시도가 Rejected이면 Payment FAILED·주문 CANCELED·재고를 복원한다")
  void failsWhenRetriedConfirmRejected() {
    Payment payment = saveInDoubtPayment("toss_pk_q");
    Order order = orderRepository.findById(payment.getOrderId()).orElseThrow();
    given(paymentGatewayClient.getPayment("toss_pk_q"))
        .willReturn(new PgPayment(PgPaymentStatus.IN_PROGRESS, null, null));
    given(paymentGatewayClient.confirm("toss_pk_q", order.getOrderNo(), payment.getAmount()))
        .willReturn(new PgConfirmResult.Rejected());

    paymentResolutionFacade.resolveInDoubt(LocalDateTime.now().plusMinutes(5));

    Payment resolved = paymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(resolved.getStatus()).isEqualTo(PaymentStatus.FAILED);

    Order failedOrder = orderRepository.findById(payment.getOrderId()).orElseThrow();
    assertThat(failedOrder.getStatus()).isEqualTo(OrderStatus.CANCELED);

    ProductStock stock = productStockRepository.findByProductId(failedOrder.getProductId()).orElseThrow();
    assertThat(stock.getOnHandQuantity()).isEqualTo(100);

    HotDealStock hotDealStock = hotDealStockRepository.findByHotDealId(failedOrder.getHotDealId()).orElseThrow();
    assertThat(hotDealStock.getRemainingQuantity()).isEqualTo(100);
  }

  @Test
  @DisplayName("IN_PROGRESS confirm 재시도가 GatewayError로 미확정이면 IN_DOUBT을 유지해 다음 회차에 넘긴다")
  void keepsInDoubtWhenRetriedConfirmUnresolved() {
    Payment payment = saveInDoubtPayment("toss_pk_r");
    Order order = orderRepository.findById(payment.getOrderId()).orElseThrow();
    given(paymentGatewayClient.getPayment("toss_pk_r"))
        .willReturn(new PgPayment(PgPaymentStatus.IN_PROGRESS, null, null));
    given(paymentGatewayClient.confirm("toss_pk_r", order.getOrderNo(), payment.getAmount()))
        .willReturn(new PgConfirmResult.GatewayError());

    paymentResolutionFacade.resolveInDoubt(LocalDateTime.now().plusMinutes(5));

    Payment kept = paymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(kept.getStatus()).isEqualTo(PaymentStatus.IN_DOUBT);

    Order keptOrder = orderRepository.findById(payment.getOrderId()).orElseThrow();
    assertThat(keptOrder.getStatus()).isEqualTo(OrderStatus.PAID);
  }

  @Test
  @DisplayName("생성 후 grace가 지나지 않은 IN_DOUBT은 해소 대상에서 제외되어 IN_DOUBT으로 유지된다")
  void skipsInDoubtWithinGrace() {
    Payment payment = saveInDoubtPayment("toss_pk_z");

    paymentResolutionFacade.resolveInDoubt(LocalDateTime.now());

    Payment kept = paymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(kept.getStatus()).isEqualTo(PaymentStatus.IN_DOUBT);
  }
}
