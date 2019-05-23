package com.wsmhz.shop.order.service.domain.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * create by tangbj on 2018/5/27
 */
@Setter
@Getter
public class ShippingDto {

    private String receiverName;

    private String receiverPhone;

    private String receiverMobile;

    private String receiverProvince;

    private String receiverCity;

    private String receiverDistrict;

    private String receiverAddress;

    private String receiverZip;
}
