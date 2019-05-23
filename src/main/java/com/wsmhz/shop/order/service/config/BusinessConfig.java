package com.wsmhz.shop.order.service.config;


import com.wsmhz.common.business.properties.BusinessProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * create by tangbj on 2018/5/20
 */
@Configuration
@EnableConfigurationProperties(BusinessProperties.class)
public class BusinessConfig {

}
