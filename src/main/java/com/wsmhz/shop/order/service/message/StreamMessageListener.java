package com.wsmhz.shop.order.service.message;

import com.rabbitmq.client.Channel;
import com.wsmhz.shop.order.service.domain.form.OrderCreateMessageForm;
import com.wsmhz.shop.order.service.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.web.context.request.*;

/**
 * Created By TangBiJing On 2019/4/11
 * Description: 消息监听处理
 */
@Slf4j
@EnableBinding(StreamChanelInput.class)
public class StreamMessageListener {

    @Autowired
    private OrderService orderService;

    @StreamListener(StreamChanelInput.ORDER_CREATE_INPUT)
    public void onMessage(Message<OrderCreateMessageForm> message, @Header(AmqpHeaders.CHANNEL) Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) Long deliveryTag) throws Exception {
       try {
           OrderCreateMessageForm messageForm = message.getPayload();
           log.info("接收到消息:{}", messageForm);
           Long userId = messageForm.getUserId();
           Long shippingId = messageForm.getShippingId();
           String messageKey = messageForm.getMessageKey();
           if(userId != null && shippingId != null){
               log.info("开始创建订单");
               orderService.createOrder(userId,shippingId,messageKey);
               //消费完毕
               channel.basicAck(deliveryTag, false);
           }else {
               log.error("参数异常，创建订单失败");
               // 丢弃消息
               channel.basicReject(deliveryTag, false);
           }
        } catch (Exception e) {
            log.error("消息监听出现异常: {}", e);
            // 丢弃消息
           throw new AmqpRejectAndDontRequeueException(message.getPayload() + "消息消费失败，放到对应的死信队列", e);
        }
    }
}
