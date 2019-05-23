package com.wsmhz.shop.order.service.service.impl;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.wsmhz.common.business.properties.BusinessProperties;
import com.wsmhz.common.business.response.ServerResponse;
import com.wsmhz.common.business.service.impl.BaseServiceImpl;
import com.wsmhz.common.business.utils.*;
import com.wsmhz.pay.pay.service.api.api.PayInfoApi;
import com.wsmhz.pay.pay.service.api.api.PayServiceApi;
import com.wsmhz.pay.pay.service.api.domain.form.PayInfoInSertForm;
import com.wsmhz.pay.pay.service.api.domain.form.ali.AliOrderItemFrom;
import com.wsmhz.pay.pay.service.api.domain.form.ali.AliPayPrecreateForm;
import com.wsmhz.pay.pay.service.api.domain.vo.AliPayCheckSignResponseVo;
import com.wsmhz.pay.pay.service.api.domain.vo.AliPayResponseVo;
import com.wsmhz.shop.order.service.domain.dto.OrderItemDto;
import com.wsmhz.shop.order.service.domain.dto.ShippingDto;
import com.wsmhz.shop.order.service.domain.entity.Order;
import com.wsmhz.shop.order.service.domain.entity.OrderItem;
import com.wsmhz.shop.order.service.domain.entity.Shipping;
import com.wsmhz.shop.order.service.domain.vo.OrderVo;
import com.wsmhz.shop.order.service.enums.OrderConst;
import com.wsmhz.shop.order.service.mapper.OrderItemMapper;
import com.wsmhz.shop.order.service.mapper.OrderMapper;
import com.wsmhz.shop.order.service.service.OrderService;
import com.wsmhz.shop.order.service.service.ShippingService;
import com.wsmhz.shop.product.service.api.api.CartApi;
import com.wsmhz.shop.product.service.api.api.ProductApi;
import com.wsmhz.shop.product.service.api.domain.form.CartDeteleForm;
import com.wsmhz.shop.product.service.api.domain.form.ProductSelectForm;
import com.wsmhz.shop.product.service.api.domain.form.ProductUpdateForm;
import com.wsmhz.shop.product.service.api.domain.form.UserCartForm;
import com.wsmhz.shop.product.service.api.domain.vo.CartSimpleVo;
import com.wsmhz.shop.product.service.api.domain.vo.ProductSimpleVo;
import com.wsmhz.shop.product.service.api.enums.ProductConst;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tk.mybatis.mapper.entity.Example;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * create by tangbj on 2018/5/27
 */
@Service
@Slf4j
public class OrderServiceImpl extends BaseServiceImpl<Order> implements OrderService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private ProductApi productApi;
    @Autowired
    private CartApi cartApi;
    @Autowired
    private ShippingService shippingService;
    @Autowired
    private RedisTemplate<String,String> redisTemplate;
    @Autowired
    private PayServiceApi payServiceApi;
    @Autowired
    private PayInfoApi payInfoApi;
    @Autowired
    private BusinessProperties businessProperties;
    @Value("${wsmhz.business.pay.notifyUrl}")
    private String notifyUrl;

    @Override
    public Order selectByUserIdAndOrderNo(Long userId, Long orderNo) {
        Example example = new Example(Order.class);
        Example.Criteria criteria = example.createCriteria();
        if(userId != null){
            criteria.andEqualTo("userId",userId);
        }
        criteria.andEqualTo("orderNo",orderNo);
        return orderMapper.selectOneByExample(example);
    }

    @Override
    public List<OrderItem> selectByOrderItemNoAndUserId(Long orderNo, Long userId) {
        Example example = new Example(OrderItem.class);
        Example.Criteria criteria = example.createCriteria();
        criteria.andEqualTo("orderNo",orderNo);
        if(userId != null){
            criteria.andEqualTo("userId",userId);
        }
        return orderItemMapper.selectByExample(example);
    }

    @Transactional
    @Override
    public ServerResponse createOrder(Long userId, Long shippingId, String messageKey) {
        //从购物车中获取数据
        List<CartSimpleVo> cartList = cartApi.selectByUserId(UserCartForm.builder().userId(userId).checked(true).build());
        //计算这个订单的总价
        ServerResponse serverResponse = this.getCartOrderItem(userId,cartList);
        if(!serverResponse.isSuccess()){
            return serverResponse;
        }
        List<OrderItem> orderItemList = (List<OrderItem>)serverResponse.getData();
        BigDecimal payment = this.getOrderTotalPrice(orderItemList);
        //生成订单
        Order order = this.assembleOrder(userId,shippingId,payment,messageKey);
        if(order == null){
            log.error("生成订单错误");
            return ServerResponse.createByErrorMessage("生成订单错误");
        }
        for(OrderItem orderItem : orderItemList){
            orderItem.setOrderNo(order.getOrderNo());
            orderItemMapper.insertSelective(orderItem); //插入订单子表数据
        }
        //减少产品的库存
        this.reduceProductStock(orderItemList);
        //清空购物车
        this.cleanCart(cartList);
        return ServerResponse.createBySuccess(order.getOrderNo());
    }

    @Override
    public ServerResponse queryCreateOrder(String queryKey) {
        String value = redisTemplate.opsForValue().get(queryKey);
        if(StringUtils.isNotBlank(value)){
            Order order = JsonUtil.stringToObj(value,Order.class);
            redisTemplate.boundValueOps(queryKey).expire(1,TimeUnit.MINUTES);
            return ServerResponse.createBySuccess("创建订单成功",order.getOrderNo());
        }
        return ServerResponse.createBySuccess();
    }

    @Override
    public ServerResponse queryOrderPayStatus(Long userId, Long orderNo) {
        Order order = selectByUserIdAndOrderNo(userId,orderNo);
        if(order == null){
            return ServerResponse.createByErrorMessage("用户没有该订单");
        }
        if(order.getStatus().getCode() >= OrderConst.OrderStatusEnum.PAID.getCode()){
            return ServerResponse.createBySuccess(order.getStatus().getCode());
        }
        return ServerResponse.createBySuccess();
    }

    @Override
    public ServerResponse cancel(Long userId,Long orderNo) {
        Order order  = selectByUserIdAndOrderNo(userId,orderNo);
        if(order == null){
            return ServerResponse.createByErrorMessage("该用户此订单不存在");
        }
        if(order.getStatus().getCode() != OrderConst.OrderStatusEnum.NO_PAY.getCode()){
            return ServerResponse.createByErrorMessage("已付款,无法取消订单");
        }
        Order updateOrder = new Order();
        updateOrder.setId(order.getId());
        updateOrder.setStatus(OrderConst.OrderStatusEnum.CANCELED);
        int row = orderMapper.updateByPrimaryKeySelective(updateOrder);
        if(row > 0){
            return ServerResponse.createBySuccess();
        }
        return ServerResponse.createByError();
    }

    @Override
    public ServerResponse<PageInfo> selectOrderListByUserId(Integer pageNum, Integer pageSize,Long userId,Long orderNo,OrderConst.OrderStatusEnum status) {
        PageInfo<Order> pageInfo = getOrderListByUserId(pageNum,pageSize,userId,orderNo,status);
        List<OrderVo> orderVoList = assembleOrderVoList(pageInfo.getList(),userId);
        PageInfo<OrderVo> voPageInfo = new PageInfo<>();
        pageInfo.setList(null);
        BeanUtils.copyProperties(pageInfo,voPageInfo);
        voPageInfo.setList(orderVoList);
        return ServerResponse.createBySuccess(voPageInfo);
    }

    @Override
    public ServerResponse selectOrderDetail(Long userId, Long id) {
        Order order = selectByPrimaryKey(id);
        if(order != null){
            List<OrderItem> orderItemList = selectByOrderItemNoAndUserId(order.getOrderNo(),userId);
            OrderVo orderVo = assembleOrderVo(order,orderItemList);
            return ServerResponse.createBySuccess(orderVo);
        }
        return  ServerResponse.createByErrorMessage("没有找到该订单");
    }

    @Override
    public ServerResponse shipment(Long id) {
        Order order  = selectByPrimaryKey(id);
        if(order == null){
            return ServerResponse.createByErrorMessage("此订单不存在");
        }
        if(order.getStatus().getCode() != OrderConst.OrderStatusEnum.PAID.getCode()){
            return ServerResponse.createByErrorMessage("未付款,无法发货");
        }
        Order updateOrder = new Order();
        updateOrder.setId(order.getId());
        updateOrder.setStatus(OrderConst.OrderStatusEnum.SHIPPED);
        updateOrder.setSendTime(new Date());
        int row = orderMapper.updateByPrimaryKeySelective(updateOrder);
        if(row > 0){
            return ServerResponse.createBySuccessMessage("发货成功");
        }
        return ServerResponse.createByError();
    }

    @Override
    public List<Order> selectByOrderStatusAndCreateDate(OrderConst.OrderStatusEnum status, Date createDate) {
        Example example = new Example(Order.class);
        Example.Criteria criteria = example.createCriteria();
        criteria.andEqualTo("status",status);
        criteria.andLessThanOrEqualTo("createDate",createDate);
        return orderMapper.selectByExample(example);
    }

    @Override
    public List<Map<String, String>> selectMonthOrders(Integer month) {
        List<Map<String, String>> List = orderMapper.selectMonthOrders(month);
        for (Map<String, String> map : List) {
            Map<String, String> targetMap = new HashMap<>();
            Map.Entry<String,String> entry = map.entrySet().iterator().next(); //第一个元素,枚举替换
            OrderConst.OrderStatusEnum status = OrderConst.OrderStatusEnum.getItem(entry.getValue());
            if(status != null){
                map.put(entry.getKey(),status.getValue());
            }
        }
        return List;
    }

    @Override
    public ServerResponse pay(Long orderNo, Long userId, String path) {
        Map<String ,String> resultMap = Maps.newHashMap();

        Order order = selectByUserIdAndOrderNo(userId,orderNo);
        if(order == null){
            return ServerResponse.createByErrorMessage("用户没有该订单");
        }
        resultMap.put("orderNo",String.valueOf(order.getOrderNo()));
        List<OrderItem> orderItemList = selectByOrderItemNoAndUserId(orderNo,userId);
        AliPayResponseVo aliPayResponseVo = payServiceApi.aliPayPrecreate(AliPayPrecreateForm.builder()
                                        .orderNo(orderNo.toString())
                                        .payment(order.getPayment().toString())
                                        .notifyUrl(notifyUrl)
                                        .orderItemList(
                                            orderItemList.stream()
                                                .map((item) ->
                                                   AliOrderItemFrom.builder()
                                                        .productId(item.getProductId().toString())
                                                        .productName(item.getProductName())
                                                        .quantity(item.getQuantity())
                                                        .price(stringToLong(item.getCurrentUnitPrice().toString()))
                                                        .body("wsmhz-shop:" + item.getProductName())
                                                           .build()
                                            ).collect(Collectors.toList())
                                        )
                                        .businessSystemName("wsmhz-shop")
                                        .platform("alipay").build());
        File folder = new File(path);
        if(!folder.exists()){
            folder.setWritable(true);
            folder.mkdirs();
        }
        // 需要修改为运行机器上的路径
        String qrPath = String.format(path+"/qr-%s.png",aliPayResponseVo.getOrderNo());
        String qrFileName = String.format("qr-%s.png",aliPayResponseVo.getOrderNo());
        ZxingUtils.getQRCodeImge(aliPayResponseVo.getQrCode(), 256, qrPath);

        File targetFile = new File(path,qrFileName);
        try {
            FTPUtil.uploadFile(Lists.newArrayList(targetFile));
        } catch (IOException e) {
            log.error("上传二维码异常",e);
        }
        log.info("qrPath:" + qrPath);
        String qrUrl = businessProperties.getFtp().getHttpPrefix()+targetFile.getName();
        resultMap.put("qrCodeUrl",qrUrl);
        return ServerResponse.createBySuccess(resultMap);
    }

    @Override
    public ServerResponse aliPayCallback(Map<String, String> params) {
        AliPayCheckSignResponseVo aliPayCheckSignResponseVo = payServiceApi.aliPayCheckSign(params);
        if( ! aliPayCheckSignResponseVo.isChecked()){
            return ServerResponse.createByErrorMessage("非法请求,验证不通过");
        }
        Long orderNo = Long.parseLong(params.get("out_trade_no"));
        String tradeNo = params.get("trade_no");
        String tradeStatus = params.get("trade_status");
        Order order = selectByUserIdAndOrderNo(null,orderNo);
        if(order == null){
            return ServerResponse.createByErrorMessage("非本商城的订单,回调忽略");
        }
        if(order.getStatus().getCode() >= OrderConst.OrderStatusEnum.PAID.getCode()){
            return ServerResponse.createBySuccess("支付宝重复调用");
        }
        if(OrderConst.AlipayCallback.TRADE_STATUS_TRADE_SUCCESS.equals(tradeStatus)){
            order.setPaymentTime(DateTimeUtil.strToDate(params.get("gmt_payment")));
            order.setStatus(OrderConst.OrderStatusEnum.PAID);
            updateByPrimaryKeySelective(order);
        }

        // 支付平台支付信息持久化
        payInfoApi.insertSelective(PayInfoInSertForm.builder()
            .userId(order.getUserId())
            .orderNo(order.getOrderNo())
            .payPlatform(OrderConst.PayPlatformEnum.ALIPAY.name())
            .platformNumber(tradeNo)
            .platformStatus(tradeStatus).build());
        return ServerResponse.createBySuccess();
    }

    @Override
    public void closeOrder(int hour) {
        Date closeDateTime = DateUtils.addHours(new Date(),-hour);
        List<Order> orderList = selectByOrderStatusAndCreateDate(OrderConst.OrderStatusEnum.NO_PAY,closeDateTime);

        for(Order order : orderList){
            List<OrderItem> orderItemList = selectByOrderItemNoAndUserId(order.getOrderNo(),null);
            for(OrderItem orderItem : orderItemList){

                //一定要用主键where条件，防止锁表。同时必须是支持MySQL的InnoDB。
                Integer stock = productApi.selectStockByProductId(orderItem.getProductId());
                //考虑到已生成的订单里的商品被删除的情况
                if(stock == null){
                    continue;
                }
                productApi.update(ProductUpdateForm.builder()
                        .id(orderItem.getProductId())
                        .stock(stock + orderItem.getQuantity()).build());
            }
            closeOrderByOrderId(order.getId());
            log.info("关闭订单OrderNo：{}",order.getOrderNo());
        }
    }

    private void closeOrderByOrderId(Long id){
        Order order = new Order();
        order.setId(id);
        order.setStatus(OrderConst.OrderStatusEnum.ORDER_CLOSE);
        updateByPrimaryKeySelective(order);
    }

    private PageInfo<Order> getOrderListByUserId(Integer pageNum, Integer pageSize, Long userId, Long orderNo,OrderConst.OrderStatusEnum status) {
        PageHelper.startPage(pageNum, pageSize);
        Example example = new Example(Order.class);
        example.setOrderByClause("update_date desc");
        Example.Criteria criteria = example.createCriteria();
        if(userId != null){
            criteria.andEqualTo("userId",userId);
        }
        if(orderNo != null){
            criteria.andEqualTo("orderNo",orderNo);
        }
        if(status != null){
            criteria.andEqualTo("status",status);
        }
        return new PageInfo<>(orderMapper.selectByExample(example));
    }

    private List<OrderVo> assembleOrderVoList(List<Order> orderList, Long userId){
        List<OrderVo> orderVoList = Lists.newArrayList();
        for(Order order : orderList){
            List<OrderItem>  orderItemList = selectByOrderItemNoAndUserId(order.getOrderNo(),userId);

            OrderVo orderVo = assembleOrderVo(order,orderItemList);
            orderVoList.add(orderVo);
        }
        return orderVoList;
    }

    private OrderVo assembleOrderVo(Order order,List<OrderItem> orderItemList){
        OrderVo orderVo = new OrderVo();
        orderVo.setId(order.getId());
        orderVo.setOrderNo(order.getOrderNo());
        orderVo.setPayment(order.getPayment());
        orderVo.setPaymentTypeDesc(order.getPaymentType().getValue());

        orderVo.setPostage(order.getPostage());
        orderVo.setStatusDesc(order.getStatus().getValue());
        orderVo.setStatus(order.getStatus().name());
        orderVo.setStatusCode(order.getStatus().getCode());

        Shipping shipping = shippingService.selectByPrimaryKey(order.getShippingId());
        if(shipping != null){
            orderVo.setShipping(assembleShippingVo(shipping));
        }

        orderVo.setPaymentTime(order.getPaymentTime());
        orderVo.setSendTime(order.getSendTime());
        orderVo.setEndTime(order.getEndTime());
        orderVo.setCreateTime(order.getCreateDate());
        orderVo.setCloseTime(order.getCloseTime());

        List<OrderItemDto> orderItemVoList = Lists.newArrayList();

        for(OrderItem orderItem : orderItemList){
            OrderItemDto orderItemDto = assembleOrderItemVo(orderItem);
            orderItemVoList.add(orderItemDto);
        }
        orderVo.setOrderItemList(orderItemVoList);
        return orderVo;
    }

    private OrderItemDto assembleOrderItemVo(OrderItem orderItem){
        OrderItemDto orderItemDto = new OrderItemDto();
        orderItemDto.setOrderNo(orderItem.getOrderNo());
        orderItemDto.setProductId(orderItem.getProductId());
        orderItemDto.setProductName(orderItem.getProductName());
        orderItemDto.setProductImage(orderItem.getProductImage());
        orderItemDto.setCurrentUnitPrice(orderItem.getCurrentUnitPrice());
        orderItemDto.setQuantity(orderItem.getQuantity());
        orderItemDto.setTotalPrice(orderItem.getTotalPrice());

        orderItemDto.setCreateTime(orderItem.getCreateDate());
        return orderItemDto;
    }

    private ShippingDto assembleShippingVo(Shipping shipping){
        ShippingDto shippingDto = new ShippingDto();
        shippingDto.setReceiverName(shipping.getReceiverName());
        shippingDto.setReceiverAddress(shipping.getReceiverAddress());
        shippingDto.setReceiverProvince(shipping.getReceiverProvince());
        shippingDto.setReceiverCity(shipping.getReceiverCity());
        shippingDto.setReceiverDistrict(shipping.getReceiverDistrict());
        shippingDto.setReceiverMobile(shipping.getReceiverMobile());
        shippingDto.setReceiverZip(shipping.getReceiverZip());
        shippingDto.setReceiverPhone(shippingDto.getReceiverPhone());
        return shippingDto;
    }


    private ServerResponse<List<OrderItem>> getCartOrderItem(Long userId,List<CartSimpleVo> cartList){
        List<OrderItem> orderItemList = Lists.newArrayList();
        if(CollectionUtils.isEmpty(cartList)){
            log.error("购物车为空");
            return ServerResponse.createByErrorMessage("购物车为空");
        }
        //校验购物车的数据,包括产品的状态和数量
        for(CartSimpleVo cartItem : cartList){
            OrderItem orderItem = new OrderItem();
            ProductSimpleVo product = productApi.selectById(ProductSelectForm.builder().id(cartItem.getProductId()).build());
            if( ! ProductConst.StatusEnum.ON_SALE.name().equals(product.getStatus())){
                log.error("产品："+product.getName()+"不是在线售卖状态");
                return ServerResponse.createByErrorMessage("产品："+product.getName()+"不是在线售卖状态");
            }
            //校验库存
            if(cartItem.getQuantity() > product.getStock()){
                log.error("产品："+product.getName()+"库存不足");
                return ServerResponse.createByErrorMessage("产品："+product.getName()+"库存不足");
            }
            orderItem.setUserId(userId);
            orderItem.setProductId(product.getId());
            orderItem.setProductName(product.getName());
            orderItem.setProductImage(product.getMainImage());
            orderItem.setCurrentUnitPrice(product.getPrice());
            orderItem.setQuantity(cartItem.getQuantity());
            orderItem.setTotalPrice(BigDecimalUtil.mul(product.getPrice().doubleValue(),cartItem.getQuantity()));
            orderItemList.add(orderItem);
        }
        return ServerResponse.createBySuccess(orderItemList);
    }

    private BigDecimal getOrderTotalPrice(List<OrderItem> orderItemList){
        BigDecimal payment = new BigDecimal("0");
        for(OrderItem orderItem : orderItemList){
            payment = BigDecimalUtil.add(payment.doubleValue(),orderItem.getTotalPrice().doubleValue());
        }
        return payment;
    }

    private Order assembleOrder(Long userId,Long shippingId,BigDecimal payment,String messageKey){
        Order order = new Order();
        long orderNo = this.generateOrderNo();
        order.setOrderNo(orderNo);
        order.setStatus(OrderConst.OrderStatusEnum.NO_PAY);
        order.setPostage(0);
        order.setPaymentType(OrderConst.PaymentTypeEnum.ONLINE_PAY);
        order.setPayment(payment);

        order.setUserId(userId);
        order.setShippingId(shippingId);
        int rowCount = orderMapper.insertSelective(order);
        if(rowCount > 0){
            redisTemplate.boundValueOps(messageKey).set(JsonUtil.objToString(order),OrderConst.redisMessage.CREATE_ORDER_MESSAGE_TIMEOUT_HOUR, TimeUnit.HOURS);
            return order;
        }
        return null;
    }

    private long generateOrderNo(){
        long currentTime =System.currentTimeMillis();
        return currentTime+new Random().nextInt(100);
    }

    private void cleanCart(List<CartSimpleVo> cartList){
        for(CartSimpleVo cart : cartList){
            cartApi.deleteApi(CartDeteleForm.builder().id(cart.getId()).build());
        }
    }

    private void reduceProductStock(List<OrderItem> orderItemList){
        for(OrderItem orderItem : orderItemList){
            ProductSimpleVo product = productApi.selectById(ProductSelectForm.builder().id(orderItem.getProductId()).build());
            product.setStock(product.getStock()-orderItem.getQuantity());
            ProductUpdateForm productUpdateForm = DozerBeanUtil.map(product, ProductUpdateForm.class);
            productApi.update(productUpdateForm);
        }
    }

    private String stringToLong(String string){
        Number num = Float.parseFloat(string)*100;
        return String.valueOf(num.intValue());
    }

}
