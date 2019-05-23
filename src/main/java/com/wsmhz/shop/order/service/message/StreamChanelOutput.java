package com.wsmhz.shop.order.service.message;

import org.springframework.cloud.stream.annotation.Output;
import org.springframework.messaging.MessageChannel;

public interface StreamChanelOutput {

    /**
     * 创建订单消息发送通道
     */
    String ORDER_CREATE_OUTPUT = "order_create_output";

    @Output(ORDER_CREATE_OUTPUT)
    MessageChannel orderCreateOutput();
}
