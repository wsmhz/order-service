package com.wsmhz.shop.order.service.controller;

import com.wsmhz.common.business.response.ServerResponse;
import com.wsmhz.shop.order.service.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * create by tangbj on 2018/7/15
 */
@RestController
@RequestMapping("/manage/report")
public class BackReportController {

    @Autowired
    private OrderService orderService;

    @GetMapping("/monthOrders/{month}")
    public ServerResponse monthOrders(@PathVariable("month")int month){
        return ServerResponse.createBySuccess(orderService.selectMonthOrders(month));
    }
}
