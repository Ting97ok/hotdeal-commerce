package com.sparta.msa.commerce.domain.hotdeal.service;

import com.sparta.msa.commerce.domain.hotdeal.dto.request.CreateHotDealRequest;
import com.sparta.msa.commerce.domain.hotdeal.entity.HotDeal;
import com.sparta.msa.commerce.domain.hotdeal.repository.HotDealRepository;
import com.sparta.msa.commerce.domain.product.entity.Product;
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
    // TODO(hotdeal-overlap): 같은 상품 ACTIVE 핫딜 기간 겹침 → HOTDEAL_PERIOD_OVERLAP(409) 미구현
    HotDeal hotDeal = HotDeal.create(request, product);
    return hotDealRepository.save(hotDeal);
  }
}
