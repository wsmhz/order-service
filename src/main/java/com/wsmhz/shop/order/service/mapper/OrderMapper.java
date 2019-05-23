package com.wsmhz.shop.order.service.mapper;

import com.wsmhz.common.business.tkMapper.MyBaseMapper;
import com.wsmhz.shop.order.service.domain.entity.Order;

import java.util.List;
import java.util.Map;

/**
 * create by tangbj on 2018/5/27
 */
public interface OrderMapper extends MyBaseMapper<Order> {
    List<Map<String,String>> selectMonthOrders(Integer month);
}
