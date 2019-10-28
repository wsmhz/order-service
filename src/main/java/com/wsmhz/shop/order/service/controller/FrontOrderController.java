package com.wsmhz.shop.order.service.controller;


import com.alipay.api.AlipayApiException;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.demo.trade.config.Configs;
import com.google.common.collect.Maps;
import com.wsmhz.common.business.annotation.UnAuth;
import com.wsmhz.common.business.response.ServerResponse;
import com.wsmhz.shop.order.service.api.api.OrderApi;
import com.wsmhz.shop.order.service.domain.entity.Order;
import com.wsmhz.shop.order.service.domain.form.OrderCreateMessageForm;
import com.wsmhz.shop.order.service.enums.OrderConst;
import com.wsmhz.shop.order.service.message.StreamMessageSend;
import com.wsmhz.shop.order.service.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.net.openssl.ciphers.Authentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;

/**
 * create by tangbj on 2018/5/27
 */
@Slf4j
@RestController
@RequestMapping("/api/order")
public class FrontOrderController implements OrderApi {

    private static final Logger logger = LoggerFactory.getLogger(FrontOrderController.class);

    @Autowired
    private OrderService orderService;
    @Autowired
    private StreamMessageSend streamMessageSend;

    @GetMapping("/page")
    public ServerResponse selectAll(@RequestParam(value = "pageNum")Integer pageNum,
                                    @RequestParam(value = "pageSize")Integer pageSize,
                                    @RequestParam(value = "orderNo",required = false)Long orderNo,
                                    @RequestParam(value = "status",required = false) OrderConst.OrderStatusEnum status,
                                    @RequestParam(value = "userId")Long userId){
        return  orderService.selectOrderListByUserId(pageNum,pageSize,userId,orderNo,status);
    }

    @GetMapping("/{id}")
    public ServerResponse select(@PathVariable("id")Long id){
        return  orderService.selectOrderDetail(null,id);
    }

    @UnAuth
    @PostMapping
    public ServerResponse insert(@RequestBody Order order){
        StringBuilder createOrderMessageKey = new StringBuilder(OrderConst.redisMessage.CREATE_ORDER_MESSAGE_)
                                                        .append(order.getUserId()).append(new Date().getTime());
        log.info("发送创建订单消息成功结果为：{}", streamMessageSend.orderCreateOutput(OrderCreateMessageForm.builder()
                .userId(order.getUserId())
                .shippingId(order.getShippingId())
                .MessageKey(createOrderMessageKey.toString()).build()));
//        orderService.createOrder(order.getUserId(),order.getShippingId(),createOrderMessageKey.toString());
        return ServerResponse.createBySuccess(createOrderMessageKey.toString());
    }

    @GetMapping("/queue/{queryKey}")
    public ServerResponse queryCreateOrder(@PathVariable("queryKey") String queryKey){
        return orderService.queryCreateOrder(queryKey);
    }

    @PostMapping("/pay")
    public ServerResponse pay(@RequestBody Order order, HttpServletRequest request){
        String path = request.getSession().getServletContext().getRealPath("upload");
        return orderService.pay(order.getOrderNo(),order.getUserId(),path);
    }

    @UnAuth
    @RequestMapping("/aliPayCallback")
    @ResponseBody
    public Object aliPayCallback(HttpServletRequest request){
        Map<String,String> params = Maps.newHashMap();

        Map requestParams = request.getParameterMap();
        for(Iterator iterator = requestParams.keySet().iterator(); iterator.hasNext();){
            String name = (String)iterator.next();
            String[] values = (String[]) requestParams.get(name);
            String valueStr = "";
            for(int i = 0 ; i <values.length;i++){
                valueStr = (i == values.length -1)?valueStr + values[i]:valueStr + values[i]+",";
            }
            params.put(name,valueStr);
        }
        logger.info("支付宝回调,sign:{},trade_status:{},参数:{}",params.get("sign"),params.get("trade_status"),params.toString());

        //验证回调的正确性,是不是支付宝发的.还要避免重复通知.
        params.remove("sign_type");
        try {
            boolean aliPayRSACheckedV2 = AlipaySignature.rsaCheckV2(params, Configs.getAlipayPublicKey(),"utf-8",Configs.getSignType());

            if(!aliPayRSACheckedV2){
                return ServerResponse.createByErrorMessage("非法请求,验证不通过");
            }
        } catch (AlipayApiException e) {
            logger.error("支付宝验证回调异常",e);
        }
        //验证各种数据
        ServerResponse serverResponse = orderService.aliPayCallback(params);
        if(serverResponse.isSuccess()){
            return OrderConst.AlipayCallback.RESPONSE_SUCCESS;
        }
        return OrderConst.AlipayCallback.RESPONSE_FAILED;
    }

    @GetMapping("/status/{userId}/{orderNo}")
    public ServerResponse queryOrderPayStatus(@PathVariable("userId") Long userId, @PathVariable("orderNo") Long orderNo){
        return orderService.queryOrderPayStatus(userId,orderNo);
    }

    @PutMapping
    public ServerResponse cancel(@RequestBody Order order){
        return orderService.cancel(order.getUserId(),order.getOrderNo());
    }
}
