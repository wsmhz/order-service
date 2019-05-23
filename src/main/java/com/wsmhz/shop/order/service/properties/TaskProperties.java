package com.wsmhz.shop.order.service.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 定时任务调度配置项
 * create by tangbj on 2018/7/14
 */
@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "wsmhz.task")
public class TaskProperties {
    /**
     * 定时关单时间,单位小时(例如关闭当前时间2小时前还未支付的订单)
     */
    private String orderCloseTimeHour;
    /**
     * redis关单的分布式锁超时时间,单位毫秒
     */
    private String orderCloseLockTimeout;

}
