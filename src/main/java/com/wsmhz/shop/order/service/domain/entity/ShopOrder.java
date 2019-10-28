package com.wsmhz.shop.order.service.domain.entity;

import java.math.BigDecimal;
import java.util.Date;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Created with Mybatis Generator Plugin
 *
 * @author wsmhz
 * Created on 2019/10/28 06:15
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopOrder {
    /**
     * 订单id
     */
    private Long id;

    /**
     * 订单号
     */
    private Long orderNo;

    /**
     * 用户id
     */
    private Long userId;

    private Long shippingId;

    /**
     * 实际付款金额,单位是元,保留两位小数
     */
    private BigDecimal payment;

    /**
     * 支付类型
     */
    private String paymentType;

    /**
     * 运费,单位是元
     */
    private Integer postage;

    /**
     * 订单状态
     */
    private String status;

    /**
     * 支付时间
     */
    private Date paymentTime;

    /**
     * 发货时间
     */
    private Date sendTime;

    /**
     * 交易完成时间
     */
    private Date endTime;

    /**
     * 交易关闭时间
     */
    private Date closeTime;

    private Date createDate;

    private Date updateDate;

    private Date deleteDate;
}