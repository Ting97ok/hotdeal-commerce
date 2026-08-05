package com.sparta.msa.commerce.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.sparta.msa.commerce.LedgerAssertions;
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
import com.sparta.msa.commerce.domain.payment.client.PaymentGatewayClient;
import com.sparta.msa.commerce.domain.payment.client.dto.PgConfirmResult;
import com.sparta.msa.commerce.domain.payment.client.dto.PgPayment;
import com.sparta.msa.commerce.domain.payment.client.dto.PgPaymentStatus;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClientException;

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

  private void assertLedger() {
    LedgerAssertions.assertLedger(
        hotDealRepository, hotDealStockRepository, orderRepository, paymentRepository);
  }

  private void assertSettledLedger() {
    LedgerAssertions.assertSettledLedger(orderRepository, paymentRepository);
  }

  private Payment saveInDoubtPayment(String paymentKey) {
    return paymentRepository.save(Payment.createInDoubt(savePaidOrder(paymentKey), paymentKey));
  }

  private Order savePaidOrder(String tag) {
    User user = userRepository.save(
        User.create(tag + "@test.com", passwordEncoder.encode("pass"), "구매자", UserRole.USER));
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
    commonOrderService.markPaid(order, LocalDateTime.now());
    return order;
  }

  @Test
  @DisplayName("결제 조회가 DONE이면 IN_DOUBT을 DONE으로 확정한다")
  void resolvesToDoneWhenGatewayDone() {
    Payment payment = saveInDoubtPayment("toss_pk_x");
    given(paymentGatewayClient.findPayment("toss_pk_x"))
        .willReturn(Optional.of(new PgPayment("toss_pk_x", PgPaymentStatus.DONE, new BigDecimal("19800"), LocalDateTime.now())));

    paymentResolutionFacade.resolveInDoubt(LocalDateTime.now().plusMinutes(5));

    Payment resolved = paymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(resolved.getStatus()).isEqualTo(PaymentStatus.DONE);
    assertLedger();
    assertSettledLedger();
  }

  @Test
  @DisplayName("결제 조회가 EXPIRED이면 Payment FAILED·주문 CANCELED·재고를 복원한다")
  void resolvesToFailedWhenGatewayExpired() {
    Payment payment = saveInDoubtPayment("toss_pk_y");
    given(paymentGatewayClient.findPayment("toss_pk_y"))
        .willReturn(Optional.of(new PgPayment("toss_pk_y", PgPaymentStatus.EXPIRED, null, null)));

    paymentResolutionFacade.resolveInDoubt(LocalDateTime.now().plusMinutes(5));

    Payment resolved = paymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(resolved.getStatus()).isEqualTo(PaymentStatus.FAILED);

    Order order = orderRepository.findById(payment.getOrderId()).orElseThrow();
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);

    ProductStock stock = productStockRepository.findByProductId(order.getProductId()).orElseThrow();
    assertThat(stock.getOnHandQuantity()).isEqualTo(100);

    HotDealStock hotDealStock = hotDealStockRepository.findByHotDealId(order.getHotDealId()).orElseThrow();
    assertThat(hotDealStock.getRemainingQuantity()).isEqualTo(100);
    assertLedger();
  }

  @Test
  @DisplayName("결제 조회가 IN_PROGRESS이면 confirm 멱등 재시도가 Approved일 때 DONE으로 확정한다")
  void retriesConfirmWhenGatewayInProgress() {
    Payment payment = saveInDoubtPayment("toss_pk_p");
    Order order = orderRepository.findById(payment.getOrderId()).orElseThrow();
    given(paymentGatewayClient.findPayment("toss_pk_p"))
        .willReturn(Optional.of(new PgPayment("toss_pk_inq", PgPaymentStatus.IN_PROGRESS, null, null)));
    given(paymentGatewayClient.confirm("toss_pk_p", order.getOrderNo(), payment.getAmount()))
        .willReturn(new PgConfirmResult.Approved("toss_pk_p", payment.getAmount(), LocalDateTime.now()));

    paymentResolutionFacade.resolveInDoubt(LocalDateTime.now().plusMinutes(5));

    Payment resolved = paymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(resolved.getStatus()).isEqualTo(PaymentStatus.DONE);
    assertLedger();
    assertSettledLedger();
  }

  @Test
  @DisplayName("IN_PROGRESS confirm 재시도가 Rejected이면 Payment FAILED·주문 CANCELED·재고를 복원한다")
  void failsWhenRetriedConfirmRejected() {
    Payment payment = saveInDoubtPayment("toss_pk_q");
    Order order = orderRepository.findById(payment.getOrderId()).orElseThrow();
    given(paymentGatewayClient.findPayment("toss_pk_q"))
        .willReturn(Optional.of(new PgPayment("toss_pk_inq", PgPaymentStatus.IN_PROGRESS, null, null)));
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
    assertLedger();
  }

  @Test
  @DisplayName("IN_PROGRESS confirm 재시도가 GatewayError로 미확정이면 IN_DOUBT을 유지해 다음 회차에 넘긴다")
  void keepsInDoubtWhenRetriedConfirmUnresolved() {
    Payment payment = saveInDoubtPayment("toss_pk_r");
    Order order = orderRepository.findById(payment.getOrderId()).orElseThrow();
    given(paymentGatewayClient.findPayment("toss_pk_r"))
        .willReturn(Optional.of(new PgPayment("toss_pk_inq", PgPaymentStatus.IN_PROGRESS, null, null)));
    given(paymentGatewayClient.confirm("toss_pk_r", order.getOrderNo(), payment.getAmount()))
        .willReturn(new PgConfirmResult.GatewayError());

    paymentResolutionFacade.resolveInDoubt(LocalDateTime.now().plusMinutes(5));

    Payment kept = paymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(kept.getStatus()).isEqualTo(PaymentStatus.IN_DOUBT);

    Order keptOrder = orderRepository.findById(payment.getOrderId()).orElseThrow();
    assertThat(keptOrder.getStatus()).isEqualTo(OrderStatus.PAID);
    assertLedger();
  }

  @Test
  @DisplayName("결제 조회가 빈 결과(토스에 결제 없음=404)면 Payment FAILED·주문 CANCELED로 확정한다 — 위조 키 잔여 루프 차단")
  void resolvesToFailedWhenGatewayHasNoPayment() {
    Payment payment = saveInDoubtPayment("toss_pk_fake");
    given(paymentGatewayClient.findPayment("toss_pk_fake")).willReturn(Optional.empty());

    paymentResolutionFacade.resolveInDoubt(LocalDateTime.now().plusMinutes(5));

    Payment resolved = paymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(resolved.getStatus()).isEqualTo(PaymentStatus.FAILED);
    Order order = orderRepository.findById(payment.getOrderId()).orElseThrow();
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
    assertLedger();
  }

  @Test
  @DisplayName("한 건의 토스 조회가 예외로 실패해도 나머지 IN_DOUBT은 해소된다")
  void resolvesRemainingWhenOneInquiryFails() {
    Payment poison = saveInDoubtPayment("toss_pk_poison");
    Payment healthy = saveInDoubtPayment("toss_pk_ok");
    given(paymentGatewayClient.findPayment("toss_pk_poison"))
        .willThrow(new RestClientException("toss inquiry failed"));
    given(paymentGatewayClient.findPayment("toss_pk_ok"))
        .willReturn(Optional.of(new PgPayment("toss_pk_x", PgPaymentStatus.DONE, new BigDecimal("19800"), LocalDateTime.now())));

    paymentResolutionFacade.resolveInDoubt(LocalDateTime.now().plusMinutes(5));

    Payment kept = paymentRepository.findById(poison.getId()).orElseThrow();
    assertThat(kept.getStatus()).isEqualTo(PaymentStatus.IN_DOUBT);
    Payment resolved = paymentRepository.findById(healthy.getId()).orElseThrow();
    assertThat(resolved.getStatus()).isEqualTo(PaymentStatus.DONE);
    assertLedger();
  }

  @Test
  @DisplayName("생성 후 grace가 지나지 않은 IN_DOUBT은 해소 대상에서 제외되어 IN_DOUBT으로 유지된다")
  void skipsInDoubtWithinGrace() {
    Payment payment = saveInDoubtPayment("toss_pk_z");

    paymentResolutionFacade.resolveInDoubt(LocalDateTime.now());

    then(paymentGatewayClient).should(never()).findPayment(payment.getPgPaymentKey());
    Payment kept = paymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(kept.getStatus()).isEqualTo(PaymentStatus.IN_DOUBT);
    assertLedger();
  }

  @Test
  @DisplayName("PAID인데 Payment가 없는 고아는 토스 조회가 DONE이면 Payment DONE을 생성해 매출을 복구한다")
  void recoversOrphanWhenGatewayDone() {
    Order orphan = savePaidOrder("orphan-done");
    given(paymentGatewayClient.findPaymentByOrderId(orphan.getOrderNo()))
        .willReturn(Optional.of(new PgPayment("toss_pk_orphan", PgPaymentStatus.DONE,
            orphan.getOrderAmount(), LocalDateTime.now())));

    paymentResolutionFacade.resolveOrphanedPaid(LocalDateTime.now().plusMinutes(16));

    List<Payment> payments = paymentRepository.findAll();
    assertThat(payments).hasSize(1);
    assertThat(payments.get(0).getStatus()).isEqualTo(PaymentStatus.DONE);
    assertThat(payments.get(0).getPgPaymentKey()).isEqualTo("toss_pk_orphan");
    assertThat(orderRepository.findById(orphan.getId()).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.PAID);
    assertLedger();
    assertSettledLedger();
  }

  @Test
  @DisplayName("고아의 토스 조회가 404(결제 없음)면 주문 CANCELED로 확정하고 핫딜·상품 재고를 방출한다")
  void failsOrphanWhenGatewayHasNoPayment() {
    Order orphan = savePaidOrder("orphan-none");
    given(paymentGatewayClient.findPaymentByOrderId(orphan.getOrderNo()))
        .willReturn(Optional.empty());

    paymentResolutionFacade.resolveOrphanedPaid(LocalDateTime.now().plusMinutes(16));

    assertThat(paymentRepository.findAll()).isEmpty();
    Order canceled = orderRepository.findById(orphan.getId()).orElseThrow();
    assertThat(canceled.getStatus()).isEqualTo(OrderStatus.CANCELED);
    HotDealStock hotDealStock = hotDealStockRepository.findByHotDealId(canceled.getHotDealId()).orElseThrow();
    assertThat(hotDealStock.getRemainingQuantity()).isEqualTo(100);
    ProductStock stock = productStockRepository.findByProductId(canceled.getProductId()).orElseThrow();
    assertThat(stock.getOnHandQuantity()).isEqualTo(100);
    assertLedger();
  }

  @Test
  @DisplayName("고아의 토스 조회가 EXPIRED면 주문 CANCELED로 확정하고 재고를 방출한다")
  void failsOrphanWhenGatewayExpired() {
    Order orphan = savePaidOrder("orphan-expired");
    given(paymentGatewayClient.findPaymentByOrderId(orphan.getOrderNo()))
        .willReturn(Optional.of(new PgPayment("toss_pk_exp", PgPaymentStatus.EXPIRED, null, null)));

    paymentResolutionFacade.resolveOrphanedPaid(LocalDateTime.now().plusMinutes(16));

    assertThat(paymentRepository.findAll()).isEmpty();
    assertThat(orderRepository.findById(orphan.getId()).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.CANCELED);
    assertLedger();
  }

  @Test
  @DisplayName("만료시각+유예(5분)가 지나지 않은 PAID 주문은 고아 스캔 대상에서 제외된다")
  void skipsOrphanWithinGrace() {
    Order orphan = savePaidOrder("orphan-grace");

    paymentResolutionFacade.resolveOrphanedPaid(LocalDateTime.now());

    then(paymentGatewayClient).should(never()).findPaymentByOrderId(orphan.getOrderNo());
    assertThat(orderRepository.findById(orphan.getId()).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.PAID);
    assertThat(paymentRepository.findAll()).isEmpty();
    assertLedger();
  }

  @Test
  @DisplayName("고아의 토스 조회가 IN_PROGRESS면 확정하지 않고 다음 회차로 남긴다")
  void keepsOrphanWhenGatewayInProgress() {
    Order orphan = savePaidOrder("orphan-progress");
    given(paymentGatewayClient.findPaymentByOrderId(orphan.getOrderNo()))
        .willReturn(Optional.of(new PgPayment("toss_pk_prog", PgPaymentStatus.IN_PROGRESS, null, null)));

    paymentResolutionFacade.resolveOrphanedPaid(LocalDateTime.now().plusMinutes(16));

    assertThat(paymentRepository.findAll()).isEmpty();
    assertThat(orderRepository.findById(orphan.getId()).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.PAID);
    assertLedger();
  }

  @Test
  @DisplayName("고아의 토스 조회가 DONE이어도 금액이 주문 금액과 다르면 자동 확정하지 않는다")
  void holdsOrphanOnAmountMismatch() {
    Order orphan = savePaidOrder("orphan-amount");
    given(paymentGatewayClient.findPaymentByOrderId(orphan.getOrderNo()))
        .willReturn(Optional.of(new PgPayment("toss_pk_bad", PgPaymentStatus.DONE,
            orphan.getOrderAmount().add(BigDecimal.ONE), LocalDateTime.now())));

    paymentResolutionFacade.resolveOrphanedPaid(LocalDateTime.now().plusMinutes(16));

    assertThat(paymentRepository.findAll()).isEmpty();
    assertThat(orderRepository.findById(orphan.getId()).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.PAID);
    assertLedger();
  }
}
