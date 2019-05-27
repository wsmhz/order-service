package com.wsmhz.shop.order.service.config;

import com.alipay.demo.trade.service.AlipayTradeService;
import com.alipay.demo.trade.service.impl.AlipayTradeServiceImpl;
import com.wsmhz.shop.order.service.properties.AliPayProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Created By TangBiJing On 2019/4/3
 * Description:
 */
@Configuration
public class PayConfiguration {

    @Autowired
    private AliPayProperties aliPayProperties;

    @Bean
    public AlipayTradeService alipayTradeService() {
        // 可使用com.alipay.demo.trade.config下的Configs初始化其他参数
        return new AlipayTradeServiceImpl.ClientBuilder()
                .setGatewayUrl(aliPayProperties.getOpenApiDomain())
                .setAppid(aliPayProperties.getAppId())
                .setPrivateKey(aliPayProperties.getPrivateKey())
                .setAlipayPublicKey(aliPayProperties.getAlipayPublicKey())
                .setSignType(aliPayProperties.getSignType())
                .build();
    }
}
