package com.wsmhz.shop.order.service.service;

import com.wsmhz.common.business.service.BaseService;
import com.wsmhz.shop.order.service.domain.entity.Shipping;

import java.util.List;

/**
 * create by tangbj on 2018/5/27
 */
public interface ShippingService extends BaseService<Shipping> {
    /**
     * 查询该用户的收货地址
     * @param userId
     * @return
     */
    List<Shipping> selectAllByUserId(Long userId);
}
