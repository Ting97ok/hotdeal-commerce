package com.sparta.msa.commerce.domain.hotdeal.service;

import static com.sparta.msa.commerce.domain.hotdeal.exception.HotDealExceptionCode.HOTDEAL_PERIOD_OVERLAP;

import com.sparta.msa.commerce.domain.hotdeal.dto.request.CreateHotDealRequest;
import com.sparta.msa.commerce.domain.hotdeal.entity.HotDeal;
import com.sparta.msa.commerce.domain.hotdeal.repository.HotDealRepository;
import com.sparta.msa.commerce.domain.product.entity.Product;
import com.sparta.msa.commerce.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HotDealAdminService {

  private final HotDealRepository hotDealRepository;

  @Transactional
  public HotDeal create(CreateHotDealRequest request, Product product) {
    if (hotDealRepository.existsOverlappingActiveHotDeal(product, request.startAt(), request.endAt())) {
      throw new DomainException(HOTDEAL_PERIOD_OVERLAP);
    }
    HotDeal hotDeal = HotDeal.create(request, product);
    return hotDealRepository.save(hotDeal);
  }
}
