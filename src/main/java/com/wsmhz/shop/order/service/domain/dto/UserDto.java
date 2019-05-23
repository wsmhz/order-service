package com.wsmhz.shop.order.service.domain.dto;

import lombok.Data;

/**
 * create by tangbj on 2018/5/21
 */
@Data
public class UserDto {
    private Long id;

    private String username;

    private Boolean status;

    private String email;

    private String phone;

    private Integer role;
}
