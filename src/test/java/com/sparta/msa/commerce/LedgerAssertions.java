package com.sparta.msa.commerce;

import static com.sparta.msa.commerce.domain.order.entity.OrderStatus.PAID;
import static com.sparta.msa.commerce.domain.order.entity.OrderStatus.PENDING;
import static com.sparta.msa.commerce.domain.payment.entity.PaymentStatus.DONE;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.summingInt;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.msa.commerce.domain.hotdeal.entity.HotDeal;
import com.sparta.msa.commerce.domain.hotdeal.repository.HotDealRepository;
import com.sparta.msa.commerce.domain.order.entity.Order;
import com.sparta.msa.commerce.domain.order.entity.OrderStatus;
import com.sparta.msa.commerce.domain.order.repository.OrderRepository;
import com.sparta.msa.commerce.domain.payment.entity.Payment;
import com.sparta.msa.commerce.domain.payment.repository.PaymentRepository;
import com.sparta.msa.commerce.domain.stock.entity.HotDealStock;
import com.sparta.msa.commerce.domain.stock.repository.HotDealStockRepository;
import java.util.Map;
import java.util.Optional;

/**
 * 주문 ADR 7절의 장부 검증식. 검사식 본문은 그 문서가 정본이다.
 */
public final class LedgerAssertions {

  private LedgerAssertions() {
  }

  public static void assertLedger(HotDealRepository hotDealRepository,
      HotDealStockRepository hotDealStockRepository,
      OrderRepository orderRepository,
      PaymentRepository paymentRepository) {
    assertStockLedger(hotDealRepository, hotDealStockRepository, orderRepository);
    assertAtMostOneApproval(paymentRepository);
    assertApprovedImpliesPaid(orderRepository, paymentRepository);
  }

  /**
   * 미확정 해소와 고아 복구가 끝난 상태에서만 성립한다. 승인 직후 기록이 유실된 주문과
   * 결과를 못 받은 결제는 그때까지 PAID 인 채 승인된 결제가 없다.
   */
  public static void assertSettledLedger(OrderRepository orderRepository,
      PaymentRepository paymentRepository) {
    Map<Long, Long> approvals = countApprovalsByOrder(paymentRepository);

    orderRepository.findAll().stream()
        .filter(order -> order.getStatus() == PAID)
        .forEach(order -> assertThat(approvals.getOrDefault(order.getId(), 0L))
            .withFailMessage("해소 후에도 PAID 주문의 승인된 결제가 1건이 아니다 — orderId=%d, 승인된 결제=%d건",
                order.getId(), approvals.getOrDefault(order.getId(), 0L))
            .isEqualTo(1L));
  }

  private static void assertStockLedger(HotDealRepository hotDealRepository,
      HotDealStockRepository hotDealStockRepository,
      OrderRepository orderRepository) {
    Map<Long, Integer> aliveQuantities = orderRepository.findAll().stream()
        .filter(order -> order.getStatus() == PENDING || order.getStatus() == PAID)
        .collect(groupingBy(Order::getHotDealId, summingInt(Order::getQuantity)));

    for (HotDeal hotDeal : hotDealRepository.findAll()) {
      // 재고 행이 없는 핫딜은 장부 밖이다. 재고 행의 존재는 이 검증식이 말하는 바가 아니다.
      Optional<HotDealStock> stock = hotDealStockRepository.findByHotDealId(hotDeal.getId());
      if (stock.isEmpty()) {
        continue;
      }
      int remaining = stock.get().getRemainingQuantity();
      int alive = aliveQuantities.getOrDefault(hotDeal.getId(), 0);
      assertThat(remaining + alive)
          .withFailMessage(
              "재고 장부 불일치 — hotDealId=%d, 총 수량=%d, 남은 재고=%d, 살아 있는 주문의 수량 합=%d",
              hotDeal.getId(), hotDeal.getTotalQuantity(), remaining, alive)
          .isEqualTo(hotDeal.getTotalQuantity());
    }
  }

  private static void assertAtMostOneApproval(PaymentRepository paymentRepository) {
    countApprovalsByOrder(paymentRepository).forEach((orderId, approvals) ->
        assertThat(approvals)
            .withFailMessage("한 주문이 승인된 결제를 둘 이상 갖는다 — orderId=%d, 승인된 결제=%d건",
                orderId, approvals)
            .isLessThanOrEqualTo(1L));
  }

  private static void assertApprovedImpliesPaid(OrderRepository orderRepository,
      PaymentRepository paymentRepository) {
    Map<Long, OrderStatus> orderStatuses = orderRepository.findAll().stream()
        .collect(toMap(Order::getId, Order::getStatus));

    paymentRepository.findAll().stream()
        .filter(payment -> payment.getStatus() == DONE)
        .forEach(payment -> assertThat(orderStatuses.get(payment.getOrderId()))
            .withFailMessage("승인된 결제인데 주문이 PAID 가 아니다 — paymentId=%d, orderId=%d, 주문 상태=%s",
                payment.getId(), payment.getOrderId(), orderStatuses.get(payment.getOrderId()))
            .isEqualTo(PAID));
  }

  private static Map<Long, Long> countApprovalsByOrder(PaymentRepository paymentRepository) {
    return paymentRepository.findAll().stream()
        .filter(payment -> payment.getStatus() == DONE)
        .collect(groupingBy(Payment::getOrderId, counting()));
  }
}
