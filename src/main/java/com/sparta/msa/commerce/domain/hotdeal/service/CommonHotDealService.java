package com.sparta.msa.commerce.domain.hotdeal.service;

import static com.sparta.msa.commerce.domain.hotdeal.exception.HotDealExceptionCode.NO_ACTIVE_DEAL;

import com.sparta.msa.commerce.domain.hotdeal.entity.HotDeal;
import com.sparta.msa.commerce.domain.hotdeal.repository.HotDealRepository;
import com.sparta.msa.commerce.domain.product.entity.Product;
import com.sparta.msa.commerce.global.exception.DomainException;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommonHotDealService {

  private final HotDealRepository hotDealRepository;

  public HotDeal getActiveHotDeal(Product product) {
    return hotDealRepository.findActiveByProduct(product, LocalDateTime.now())
        .orElseThrow(() -> new DomainException(NO_ACTIVE_DEAL));
  }
}
