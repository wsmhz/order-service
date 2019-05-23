package com.wsmhz.shop.order.service.message;


import com.wsmhz.shop.order.service.domain.form.OrderCreateMessageForm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

/**
 * Created By TangBiJing On 2019/4/11
 * Description:
 */
@EnableBinding(StreamChanelOutput.class)
public class StreamMessageSendImpl implements StreamMessageSend {

    @Autowired
    private StreamChanelOutput streamChanelOutput;

    @Override
    public boolean orderCreateOutput(OrderCreateMessageForm orderCreateMessageForm) {
        Message<OrderCreateMessageForm> message = MessageBuilder.withPayload(orderCreateMessageForm).build();
        return streamChanelOutput.orderCreateOutput().send(message);
    }
}
