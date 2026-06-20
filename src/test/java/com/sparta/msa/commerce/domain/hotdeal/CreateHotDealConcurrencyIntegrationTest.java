package com.sparta.msa.commerce.domain.hotdeal;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.msa.commerce.domain.hotdeal.dto.request.CreateHotDealRequest;
import com.sparta.msa.commerce.domain.hotdeal.facade.HotDealAdminFacade;
import com.sparta.msa.commerce.domain.hotdeal.repository.HotDealRepository;
import com.sparta.msa.commerce.domain.product.entity.Product;
import com.sparta.msa.commerce.domain.product.repository.ProductRepository;
import com.sparta.msa.commerce.domain.stock.entity.ProductStock;
import com.sparta.msa.commerce.domain.stock.repository.HotDealStockRepository;
import com.sparta.msa.commerce.domain.stock.repository.ProductStockRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("핫딜 등록 동시성")
class CreateHotDealConcurrencyIntegrationTest {

  @Autowired
  HotDealAdminFacade hotDealAdminFacade;
  @Autowired
  HotDealRepository hotDealRepository;
  @Autowired
  HotDealStockRepository hotDealStockRepository;
  @Autowired
  ProductRepository productRepository;
  @Autowired
  ProductStockRepository productStockRepository;

  @BeforeEach
  void setUp() {
    hotDealRepository.deleteAll();
    hotDealStockRepository.deleteAll();
    productStockRepository.deleteAll();
    productRepository.deleteAll();
  }

  @Test
  @DisplayName("같은 상품에 동시 등록이 몰려도 예약은 가용을 넘지 않고 오버셀이 0이다")
  void concurrentReserveNeverOversells() throws Exception {
    Product product = productRepository.save(Product.create("맥북 프로", new BigDecimal("2000000")));
    int onHand = 100;
    int quantityPerDeal = 10;
    int threadCount = 20;
    productStockRepository.save(ProductStock.create(product.getId(), onHand));

    LocalDateTime base = LocalDateTime.now().plusDays(1);
    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger();

    for (int i = 0; i < threadCount; i++) {
      int index = i;
      pool.submit(() -> {
        ready.countDown();
        try {
          start.await();
          LocalDateTime dealStart = base.plusHours(index * 3L);
          CreateHotDealRequest request = new CreateHotDealRequest(
              product.getId(),
              new BigDecimal("9900"),
              quantityPerDeal,
              dealStart,
              dealStart.plusHours(2));
          hotDealAdminFacade.createHotDeal(request);
          successCount.incrementAndGet();
        } catch (Exception ignored) {
        }
      });
    }

    ready.await();
    start.countDown();
    pool.shutdown();
    pool.awaitTermination(30, TimeUnit.SECONDS);

    ProductStock productStock = productStockRepository.findByProductId(product.getId()).orElseThrow();
    assertThat(productStock.getReservedQuantity()).isLessThanOrEqualTo(productStock.getOnHandQuantity());
    assertThat(productStock.getReservedQuantity()).isEqualTo(onHand);
    assertThat(successCount.get()).isEqualTo(onHand / quantityPerDeal);
    assertThat(hotDealRepository.count()).isEqualTo(onHand / quantityPerDeal);
  }
}
