package com.wsmhz.shop.order.service.service.impl;

import com.alipay.api.AlipayResponse;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.demo.trade.model.ExtendParams;
import com.alipay.demo.trade.model.GoodsDetail;
import com.alipay.demo.trade.model.builder.AlipayTradePrecreateRequestBuilder;
import com.alipay.demo.trade.model.result.AlipayF2FPrecreateResult;
import com.alipay.demo.trade.service.AlipayTradeService;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.wsmhz.common.business.properties.BusinessProperties;
import com.wsmhz.common.business.response.ServerResponse;
import com.wsmhz.common.business.service.impl.BaseServiceImpl;
import com.wsmhz.common.business.utils.*;
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
    private BusinessProperties businessProperties;
    @Value("${wsmhz.business.pay.notifyUrl}")
    private String notifyUrl;
    @Autowired
    private AlipayTradeService tradeService;

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

        // (必填) 商户网站订单系统中唯一订单号，64个字符以内，只能包含字母、数字、下划线，
        // 需保证商户系统端不能重复，建议通过数据库sequence生成，
        String outTradeNo = order.getOrderNo().toString();

        // (必填) 订单标题，粗略描述用户的支付目的。如“xxx品牌xxx门店当面付扫码消费”
        String subject = new StringBuilder().append("wsmhzShop扫码支付,订单号:").append(outTradeNo).toString();

        // (必填) 订单总金额，单位为元，不能超过1亿元
        // 如果同时传入了【打折金额】,【不可打折金额】,【订单总金额】三者,则必须满足如下条件:【订单总金额】=【打折金额】+【不可打折金额】
        String totalAmount = order.getPayment().toString();

        // (可选) 订单不可打折金额，可以配合商家平台配置折扣活动，如果酒水不参与打折，则将对应金额填写至此字段
        // 如果该值未传入,但传入了【订单总金额】,【打折金额】,则该值默认为【订单总金额】-【打折金额】
        String undiscountableAmount = "0";

        // 卖家支付宝账号ID，用于支持一个签约账号下支持打款到不同的收款账号，(打款到sellerId对应的支付宝账号)
        // 如果该字段为空，则默认为与支付宝签约的商户的PID，也就是appid对应的PID
        String sellerId = "";

        // 订单描述，可以对交易或商品进行一个详细地描述，比如填写"购买商品2件共15.00元"
        String body = new StringBuilder().append("订单").append(outTradeNo).append("购买商品共").append(totalAmount).append("元").toString();

        // 商户操作员编号，添加此参数可以为商户操作员做销售统计
        String operatorId = "test_operator_id";

        // (必填) 商户门店编号，通过门店号和商家后台可以配置精准到门店的折扣信息，详询支付宝技术支持
        String storeId = "test_store_id";

        // 业务扩展参数，目前可添加由支付宝分配的系统商编号(通过setSysServiceProviderId方法)，详情请咨询支付宝技术支持
        ExtendParams extendParams = new ExtendParams();
        extendParams.setSysServiceProviderId("2088100200300400500");


        // 支付超时，定义为120分钟
        String timeoutExpress = "120m";

        // 商品明细列表，需填写购买商品详细信息，
        List<GoodsDetail> goodsDetailList = new ArrayList<>();

        List<OrderItem> orderItemList = selectByOrderItemNoAndUserId(orderNo,userId);
        for(OrderItem orderItem : orderItemList){
            GoodsDetail goods = GoodsDetail.newInstance(orderItem.getProductId().toString(), orderItem.getProductName(),
                    BigDecimalUtil.mul(orderItem.getCurrentUnitPrice().doubleValue(),new Double(100).doubleValue()).longValue(),
                    orderItem.getQuantity());
            goodsDetailList.add(goods);
        }

        // 创建扫码支付请求builder，设置请求参数
        AlipayTradePrecreateRequestBuilder builder = new AlipayTradePrecreateRequestBuilder()
                .setSubject(subject).setTotalAmount(totalAmount).setOutTradeNo(outTradeNo)
                .setUndiscountableAmount(undiscountableAmount).setSellerId(sellerId).setBody(body)
                .setOperatorId(operatorId).setStoreId(storeId).setExtendParams(extendParams)
                .setTimeoutExpress(timeoutExpress)
                .setNotifyUrl("http://www.wsmhz.cn/api/order/aliPayCallback")//支付宝服务器主动通知商户服务器里指定的页面http路径,根据需要设置
                .setGoodsDetailList(goodsDetailList);

        AlipayF2FPrecreateResult result = tradeService.tradePrecreate(builder);
        switch (result.getTradeStatus()) {
            case SUCCESS:
                log.info("支付宝预下单成功: )");

                AlipayTradePrecreateResponse response = result.getResponse();
                dumpResponse(response);

                File folder = new File(path);
                if(!folder.exists()){
                    folder.setWritable(true);
                    folder.mkdirs();
                }

                // 需要修改为运行机器上的路径
                //细节细节细节
                String qrPath = String.format(path+"/qr-%s.png",response.getOutTradeNo());
                String qrFileName = String.format("qr-%s.png",response.getOutTradeNo());
                ZxingUtils.getQRCodeImge(response.getQrCode(), 256, qrPath);

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
            case FAILED:
                log.error("支付宝预下单失败!!!");
                return ServerResponse.createByErrorMessage("支付宝预下单失败!!!");

            case UNKNOWN:
                log.error("系统异常，预下单状态未知!!!");
                return ServerResponse.createByErrorMessage("系统异常，预下单状态未知!!!");

            default:
                log.error("不支持的交易状态，交易返回异常!!!");
                return ServerResponse.createByErrorMessage("不支持的交易状态，交易返回异常!!!");
        }
    }

    @Override
    public ServerResponse aliPayCallback(Map<String, String> params) {
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
//
//        // 支付平台支付信息持久化
//        PayInfo payInfo = new PayInfo();
//        payInfo.setUserId(order.getUserId());
//        payInfo.setOrderNo(order.getOrderNo());
//        payInfo.setPayPlatform(OrderConst.PayPlatformEnum.ALIPAY);
//        payInfo.setPlatformNumber(tradeNo);
//        payInfo.setPlatformStatus(tradeStatus);
//
//        payInfoMapper.insertSelective(payInfo);
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

    // 简单打印应答
    private void dumpResponse(AlipayResponse response) {
        if (response != null) {
            log.info(String.format("code:%s, msg:%s", response.getCode(), response.getMsg()));
            if (StringUtils.isNotEmpty(response.getSubCode())) {
                log.info(String.format("subCode:%s, subMsg:%s", response.getSubCode(),
                        response.getSubMsg()));
            }
            log.info("body:" + response.getBody());
        }
    }

}
