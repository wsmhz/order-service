package com.wsmhz.shop.order.service.service.impl;

import com.wsmhz.common.business.service.impl.BaseServiceImpl;
import com.wsmhz.shop.order.service.domain.entity.Shipping;
import com.wsmhz.shop.order.service.mapper.ShippingMapper;
import com.wsmhz.shop.order.service.service.ShippingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tk.mybatis.mapper.entity.Example;

import java.util.List;

/**
 * create by tangbj on 2018/5/27
 */
@Service
public class ShippingServiceImpl extends BaseServiceImpl<Shipping> implements ShippingService {

    @Autowired
    private ShippingMapper shippingMapper;

    @Override
    public List<Shipping> selectAllByUserId(Long userId) {
        Example example = new Example(Shipping.class);
        Example.Criteria criteria = example.createCriteria();
        example.setOrderByClause("update_date desc");
        criteria.andEqualTo("userId",userId);
        return shippingMapper.selectByExample(example);
    }
}
