package com.wsmhz.shop.order.service.message;

import org.springframework.cloud.stream.annotation.Input;
import org.springframework.messaging.MessageChannel;

public interface StreamChanelInput {

    /**
     * 创建订单消息接收通道
     */
    String ORDER_CREATE_INPUT = "order_create_input";

    @Input(ORDER_CREATE_INPUT)
    MessageChannel orderCreateInput();
}
