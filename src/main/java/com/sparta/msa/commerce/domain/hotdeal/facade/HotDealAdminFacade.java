package com.sparta.msa.commerce.domain.hotdeal.facade;

import com.sparta.msa.commerce.domain.hotdeal.dto.request.CreateHotDealRequest;
import com.sparta.msa.commerce.domain.hotdeal.dto.response.CreateHotDealResponse;
import com.sparta.msa.commerce.domain.hotdeal.entity.HotDeal;
import com.sparta.msa.commerce.domain.hotdeal.mapper.HotDealMapper;
import com.sparta.msa.commerce.domain.hotdeal.service.HotDealAdminService;
import com.sparta.msa.commerce.domain.product.entity.Product;
import com.sparta.msa.commerce.domain.product.service.ProductService;
import com.sparta.msa.commerce.domain.stock.service.HotDealStockService;
import com.sparta.msa.commerce.domain.stock.service.ProductStockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HotDealAdminFacade {

  private final ProductService productService;
  private final HotDealAdminService hotDealAdminService;
  private final HotDealStockService hotDealStockService;
  private final ProductStockService productStockService;
  private final HotDealMapper hotDealMapper;

  @Transactional
  public CreateHotDealResponse createHotDeal(CreateHotDealRequest request) {
    Product product = productService.getProduct(request.productId());
    HotDeal hotDeal = hotDealAdminService.create(request, product);
    productStockService.reserve(request.productId(), request.totalQuantity());
    hotDealStockService.createForHotDeal(hotDeal.getId(), request.totalQuantity());
    return hotDealMapper.toCreateResponse(hotDeal);
  }
}
