package com.wsmhz.shop.order.service.message;


import com.wsmhz.shop.order.service.domain.form.OrderCreateMessageForm;

/**
 * Created By TangBiJing On 2019/4/11
 * Description:
 */
public interface StreamMessageSend {

   boolean orderCreateOutput(OrderCreateMessageForm orderCreateMessageForm);

}
