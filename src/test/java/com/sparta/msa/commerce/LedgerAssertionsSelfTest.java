package com.sparta.msa.commerce;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.msa.commerce.domain.hotdeal.dto.request.CreateHotDealRequest;
import com.sparta.msa.commerce.domain.hotdeal.entity.HotDeal;
import com.sparta.msa.commerce.domain.hotdeal.repository.HotDealRepository;
import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.domain.order.repository.OrderRepository;
import com.sparta.msa.commerce.domain.order.service.CommonOrderService;
import com.sparta.msa.commerce.domain.payment.client.dto.PgConfirmResult;
import com.sparta.msa.commerce.domain.payment.entity.Payment;
import com.sparta.msa.commerce.domain.payment.repository.PaymentRepository;
import com.sparta.msa.commerce.domain.product.entity.Product;
import com.sparta.msa.commerce.domain.product.repository.ProductRepository;
import com.sparta.msa.commerce.domain.stock.entity.HotDealStock;
import com.sparta.msa.commerce.domain.stock.repository.HotDealStockRepository;
import com.sparta.msa.commerce.domain.user.entity.User;
import com.sparta.msa.commerce.domain.user.entity.UserRole;
import com.sparta.msa.commerce.domain.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("장부 검증식 자기 검증")
class LedgerAssertionsSelfTest {

  @Autowired PasswordEncoder passwordEncoder;
  @Autowired UserRepository userRepository;
  @Autowired ProductRepository productRepository;
  @Autowired HotDealRepository hotDealRepository;
  @Autowired HotDealStockRepository hotDealStockRepository;
  @Autowired OrderRepository orderRepository;
  @Autowired PaymentRepository paymentRepository;
  @Autowired CommonOrderService commonOrderService;

  private final AtomicInteger sequence = new AtomicInteger();

  @BeforeEach
  void setUp() {
    paymentRepository.deleteAll();
    orderRepository.deleteAll();
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

  private HotDeal saveHotDeal(int totalQuantity, int remainingQuantity) {
    Product product = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));
    HotDeal hotDeal = hotDealRepository.save(HotDeal.create(new CreateHotDealRequest(
        product.getId(), new BigDecimal("9900"), totalQuantity, 5,
        LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1)), product));
    hotDealStockRepository.save(HotDealStock.create(hotDeal.getId(), remainingQuantity));
    return hotDeal;
  }

  private Order savePendingOrder(HotDeal hotDeal, int quantity) {
    User user = userRepository.save(User.create(
        "buyer" + sequence.incrementAndGet() + "@test.com", passwordEncoder.encode("pass"),
        "구매자", UserRole.USER));
    return orderRepository.save(
        Order.create(user, hotDeal, hotDeal.getProduct(), quantity, Duration.ofMinutes(10)));
  }

  private Order savePaidOrder(HotDeal hotDeal, int quantity) {
    Order order = savePendingOrder(hotDeal, quantity);
    commonOrderService.markPaid(order, LocalDateTime.now());
    return orderRepository.findById(order.getId()).orElseThrow();
  }

  private Payment saveApprovedPayment(Order order, String pgPaymentKey) {
    return paymentRepository.save(Payment.create(order, new PgConfirmResult.Approved(
        pgPaymentKey, order.getOrderAmount(), LocalDateTime.now())));
  }

  @Nested
  @DisplayName("재고 장부 — 총 수량 = 남은 재고 + 살아 있는 주문의 수량 합")
  class StockLedger {

    @Test
    @DisplayName("남은 재고와 살아 있는 주문의 합이 총 수량에 못 미치면 검사가 실패한다")
    void detectsMissingQuantity() {
      HotDeal hotDeal = saveHotDeal(100, 98);
      savePendingOrder(hotDeal, 1);

      assertThatThrownBy(LedgerAssertionsSelfTest.this::assertLedger)
          .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("남은 재고와 살아 있는 주문의 합이 총 수량을 넘으면 검사가 실패한다")
    void detectsExcessQuantity() {
      HotDeal hotDeal = saveHotDeal(100, 100);
      savePendingOrder(hotDeal, 2);

      assertThatThrownBy(LedgerAssertionsSelfTest.this::assertLedger)
          .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("취소된 주문은 살아 있는 주문에서 빠져 장부에 영향을 주지 않는다")
    void ignoresCanceledOrders() {
      HotDeal hotDeal = saveHotDeal(100, 100);
      Order canceled = savePendingOrder(hotDeal, 2);
      canceled.expire();
      orderRepository.save(canceled);

      assertThatCode(LedgerAssertionsSelfTest.this::assertLedger).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("남은 재고와 살아 있는 주문의 합이 총 수량과 같으면 통과한다")
    void passesOnBalancedLedger() {
      HotDeal hotDeal = saveHotDeal(100, 98);
      savePendingOrder(hotDeal, 2);

      assertThatCode(LedgerAssertionsSelfTest.this::assertLedger).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("결제 장부 — 주문당 승인된 결제 ≤ 1")
  class DoubleApproval {

    @Test
    @DisplayName("한 주문에 승인된 결제가 2건이면 검사가 실패한다")
    void detectsDoubleApproval() {
      HotDeal hotDeal = saveHotDeal(100, 99);
      Order order = savePaidOrder(hotDeal, 1);
      saveApprovedPayment(order, "toss_pk_first");
      saveApprovedPayment(order, "toss_pk_second");

      assertThatThrownBy(LedgerAssertionsSelfTest.this::assertLedger)
          .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("한 주문에 승인된 결제가 1건이면 통과한다")
    void passesOnSingleApproval() {
      HotDeal hotDeal = saveHotDeal(100, 99);
      Order order = savePaidOrder(hotDeal, 1);
      saveApprovedPayment(order, "toss_pk_single");

      assertThatCode(LedgerAssertionsSelfTest.this::assertLedger).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("결제 장부 — 승인된 결제가 있으면 그 주문은 PAID")
  class ApprovedImpliesPaid {

    @Test
    @DisplayName("취소된 주문에 승인된 결제가 달려 있으면 검사가 실패한다")
    void detectsApprovedPaymentOnCanceledOrder() {
      HotDeal hotDeal = saveHotDeal(100, 100);
      Order canceled = savePendingOrder(hotDeal, 1);
      canceled.expire();
      orderRepository.save(canceled);
      saveApprovedPayment(canceled, "toss_pk_orphaned");

      assertThatThrownBy(LedgerAssertionsSelfTest.this::assertLedger)
          .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("미확정 결제는 주문이 PAID인 채로 남아 있어도 통과한다")
    void passesOnInDoubtPayment() {
      HotDeal hotDeal = saveHotDeal(100, 99);
      Order order = savePaidOrder(hotDeal, 1);
      paymentRepository.save(Payment.createInDoubt(order, "toss_pk_in_doubt"));

      assertThatCode(LedgerAssertionsSelfTest.this::assertLedger).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("종국 장부 — 해소하면 PAID 주문마다 승인된 결제 1건")
  class SettledLedger {

    @Test
    @DisplayName("PAID 주문에 결제가 없으면 종국 검사가 실패한다")
    void detectsOrphanedPaidOrder() {
      HotDeal hotDeal = saveHotDeal(100, 99);
      savePaidOrder(hotDeal, 1);

      assertThatThrownBy(LedgerAssertionsSelfTest.this::assertSettledLedger)
          .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("PAID 주문에 미확정 결제만 있으면 종국 검사가 실패한다")
    void detectsUnresolvedPayment() {
      HotDeal hotDeal = saveHotDeal(100, 99);
      Order order = savePaidOrder(hotDeal, 1);
      paymentRepository.save(Payment.createInDoubt(order, "toss_pk_unresolved"));

      assertThatThrownBy(LedgerAssertionsSelfTest.this::assertSettledLedger)
          .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("PAID 주문마다 승인된 결제가 1건이면 종국 검사가 통과한다")
    void passesOnSettledLedger() {
      HotDeal hotDeal = saveHotDeal(100, 99);
      Order order = savePaidOrder(hotDeal, 1);
      saveApprovedPayment(order, "toss_pk_settled");

      assertThatCode(LedgerAssertionsSelfTest.this::assertSettledLedger).doesNotThrowAnyException();
    }
  }
}
