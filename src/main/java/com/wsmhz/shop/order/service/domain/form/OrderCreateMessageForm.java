package com.wsmhz.shop.order.service.domain.form;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Created By TangBiJing On 2019/5/16
 * Description:
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreateMessageForm {

    private Long userId;

    private Long shippingId;

    private String MessageKey;
}
