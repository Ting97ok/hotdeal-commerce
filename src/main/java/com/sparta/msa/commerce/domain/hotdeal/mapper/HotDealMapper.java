package com.sparta.msa.commerce.domain.hotdeal.mapper;

import com.sparta.msa.commerce.domain.hotdeal.dto.response.CreateHotDealResponse;
import com.sparta.msa.commerce.domain.hotdeal.entity.HotDeal;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface HotDealMapper {

  @Mapping(source = "id", target = "hotDealId")
  CreateHotDealResponse toCreateResponse(HotDeal hotDeal);
}
