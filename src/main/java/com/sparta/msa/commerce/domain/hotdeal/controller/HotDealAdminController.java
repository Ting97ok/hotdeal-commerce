package com.sparta.msa.commerce.domain.hotdeal.controller;

import com.sparta.msa.commerce.domain.hotdeal.dto.request.CreateHotDealRequest;
import com.sparta.msa.commerce.domain.hotdeal.dto.response.CreateHotDealResponse;
import com.sparta.msa.commerce.domain.hotdeal.facade.HotDealAdminFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/hotdeals")
public class HotDealAdminController {

  private final HotDealAdminFacade hotDealAdminFacade;

  @PostMapping
  public CreateHotDealResponse createHotDeal(@RequestBody @Valid CreateHotDealRequest request) {
    return hotDealAdminFacade.createHotDeal(request);
  }
}
