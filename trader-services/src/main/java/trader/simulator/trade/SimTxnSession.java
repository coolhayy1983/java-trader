package trader.simulator.trade;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import trader.common.beans.BeansContainer;
import trader.common.exception.AppException;
import trader.common.exchangeable.Exchangeable;
import trader.common.util.ConversionUtil;
import trader.common.util.DateUtil;
import trader.common.util.FileUtil;
import trader.common.util.PriceUtil;
import trader.service.ServiceConstants.ConnState;
import trader.service.md.MarketData;
import trader.service.md.MarketDataListener;
import trader.service.md.MarketDataService;
import trader.service.trade.Account;
import trader.service.trade.FutureFeeEvaluator;
import trader.service.trade.Order;
import trader.service.trade.OrderStateTuple;
import trader.service.trade.TradeConstants;
import trader.service.trade.TxnFeeEvaluator;
import trader.service.trade.spi.AbsTxnSession;
import trader.service.trade.spi.TxnSessionListener;
import trader.simulator.SimMarketTimeAware;
import trader.simulator.SimMarketTimeService;
import trader.simulator.trade.SimOrder.SimOrderState;
import trader.simulator.trade.SimResponse.ResponseType;

/**
 * 模拟行情连接
 */
public class SimTxnSession extends AbsTxnSession implements TradeConstants, SimMarketTimeAware, MarketDataListener {
    private final static Logger logger = LoggerFactory.getLogger(SimTxnSession.class);

    private MarketDataService mdService;
    private long[] money = new long[AccMoney_Count];
    private SimMarketTimeService mtService;
    private Map<Exchangeable, SimPosition> positions = new HashMap<>();
    private List<SimOrder> allOrders = new ArrayList<>();
    private List<SimTxn> allTxns = new ArrayList<>();
    private List<SimResponse> pendingResponses = new ArrayList<>();
    private TxnFeeEvaluator feeEvaluator;

    public SimTxnSession(BeansContainer beansContainer, Account account, TxnSessionListener listener) {
        super(beansContainer, account, listener);
        mdService = beansContainer.getBean(MarketDataService.class);
        mdService.addListener(this);
        mtService = beansContainer.getBean(SimMarketTimeService.class);
        mtService.addListener(this);
    }

    @Override
    public String getProvider() {
        return "simtxn";
    }

    @Override
    public LocalDate getTradingDay() {
        return tradingDay;
    }

    public TxnFeeEvaluator getFeeEvaluator() {
        return feeEvaluator;
    }

    public MarketData getLastMarketData(Exchangeable e) {
        return mdService.getLastData(e);
    }

    @Override
    public void connect() {
        changeState(ConnState.Connecting);
        try {
            Properties connProps = account.getConnectionProps();
            double initMoney = ConversionUtil.toDouble(connProps.getProperty("initMoney"), true);
            if ( initMoney==0.0 ) {
                initMoney = 50000.00;
            }
            money[TradeConstants.AccMoney_Balance] = PriceUtil.price2long(initMoney);
            money[TradeConstants.AccMoney_Available] = PriceUtil.price2long(initMoney);

            String commissionsFile = connProps.getProperty("commissionsFile");
            feeEvaluator = FutureFeeEvaluator.fromJson(null, (JsonObject)(new JsonParser()).parse(FileUtil.read(new File(commissionsFile))));
        } catch (Throwable t) {
            logger.error("Connect failed", t);
            changeState(ConnState.ConnectFailed);
        }
    }

    @Override
    public String syncLoadFeeEvaluator(Collection<Exchangeable> subscriptions) throws Exception
    {
        return feeEvaluator.toJson().toString();
    }

    @Override
    public String syncConfirmSettlement() throws Exception {
        return null;
    }

    @Override
    public long[] syncQryAccounts() throws Exception {
        long[] result = new long[money.length];
        System.arraycopy(money, 0, result, 0, result.length);
        return result;
    }

    @Override
    public String syncQryPositions() throws Exception {
        JsonObject posInfos = new JsonObject();
        for(Exchangeable e:positions.keySet()) {
            posInfos.add(e.toString(), positions.get(e).toJson());
        }
        return posInfos.toString();
    }

    @Override
    public void asyncSendOrder(Order order0) throws AppException
    {
        Exchangeable e = order0.getExchangeable();
        SimOrder order = new SimOrder(order0, mtService.getMarketTime());
        checkNewOrder(order);
        allOrders.add(order);
        if ( order.getState()==SimOrderState.Placed ) {
            SimPosition pos = positions.get(e);
            if ( pos==null ) {
                pos = new SimPosition(this, e);
                positions.put(order.getExchangeable(), pos);
            }
            pos.addOrder(order);
            //更新账户数据
            pos.updateOnMarketData(mdService.getLastData(e));
            updateAccount();
            respondLater(e, ResponseType.RtnOrder, order);
        }else {
            respondLater(e, ResponseType.RspOrderInsert, order);
        }
    }

    @Override
    public void asyncCancelOrder(Order order0) throws AppException {
        Exchangeable e = order0.getExchangeable();
        SimOrder order = null;
        SimPosition pos = positions.get(e);
        if ( pos!=null ) {
            order = pos.removeOrder(order0.getRef());
        }
        if ( order!=null ) {
            cancelOrder(order);
            //更新账户数据
            pos.updateOnMarketData(mdService.getLastData(e));
            updateAccount();
            respondLater(e, ResponseType.RtnOrder, order);
        }else {
            //返回无对应报单错误
            respondLater(e, ResponseType.RspOrderAction, order0);
        }
    }

    @Override
    protected void closeImpl() {
    }

    @Override
    public void onMarketData(MarketData md) {
        if (tradingDay==null) {
            tradingDay = DateUtil.str2localdate(md.tradingDay);
        }
        SimPosition pos = positions.get(md.instrumentId);
        if ( pos!=null ) {
            for(SimOrder order:pos.getOrders()) {
                SimTxn txn = completeOrder(order, md);
                if ( txn!=null ) {
                    pos.updateOnTxn(txn, md.updateTime);
                    respondLater(order.getExchangeable(), ResponseType.RtnTrade, txn);
                    respondLater(order.getExchangeable(), ResponseType.RtnOrder, order);
                }
            }
            pos.updateOnMarketData(md);
        }
        updateAccount();
    }

    @Override
    public void onTimeChanged(LocalDate tradingDay, LocalDateTime actionTime) {
        if ( !pendingResponses.isEmpty() ) {
            sendResponses();
            pendingResponses.clear();
        }
    }

    private void respondLater(Exchangeable e, ResponseType responseType, Object data) {
        pendingResponses.add(new SimResponse(e, responseType, data));
    }

    /**
     * 实际发送通知
     */
    private void sendResponses() {
        for(SimResponse r:pendingResponses) {
            long currTime = DateUtil.localdatetime2long(r.getExchangeable().exchange().getZoneId(), mtService.getMarketTime());
            switch(r.getType()) {
            case RspOrderInsert:
            {
                SimOrder order = (SimOrder)r.getData();
                listener.changeOrderState(order.getRef(), new OrderStateTuple(OrderState.Failed, OrderSubmitState.InsertRejected, currTime, order.getErrorReason()), null);
            }
            break;
            case RspOrderAction:
            {
                Order order = (Order)r.getData();
                listener.changeOrderState(order.getRef(), new OrderStateTuple(OrderState.Failed, OrderSubmitState.CancelRejected, currTime, "取消失败"), null);
            }
            break;
            case RtnOrder:
            {
                SimOrder order = (SimOrder)r.getData();
                Map<String, String> attrs = new HashMap<>();
                attrs.put(Order.ATTR_SYS_ID, order.getSysId());

                if ( order.getState()==SimOrderState.Placed ) {
                    //报单成功
                    listener.changeOrderState(order.getRef(), new OrderStateTuple(OrderState.Submitted, OrderSubmitState.InsertSubmitted, currTime, order.getErrorReason()), attrs);
                }else if ( order.getState()==SimOrderState.Canceled) {
                    //撤单成功
                    listener.changeOrderState(order.getRef(), new OrderStateTuple(OrderState.Canceled, OrderSubmitState.CancelSubmitted, currTime, order.getErrorReason()), attrs);
                }else if ( order.getState()==SimOrderState.Completed){
                    //全部成功交易
                    listener.changeOrderState(order.getRef(), new OrderStateTuple(OrderState.Complete, OrderSubmitState.CancelSubmitted, currTime, order.getErrorReason()), attrs);
                }else {
                    logger.error("Invalid order state: "+order.getState());
                }
            }
            break;
            case RtnTrade:
            {
                SimTxn txn = (SimTxn)r.getData();
                listener.createTransaction(
                        txn.getId(),
                        txn.getOrder().getRef(),
                        txn.getOrder().getDirection(),
                        txn.getOrder().getOffsetFlag(),
                        txn.getPrice(),
                        txn.getVolume(),
                        currTime,
                        txn
                        );
            }
            break;
            }
        }
    }

    /**
     * 校验新报单
     */
    private void checkNewOrder(SimOrder order) {
        Exchangeable e = order.getExchangeable();
        MarketData lastMd = mdService.getLastData(e);
        //检查是否有行情
        if ( lastMd==null ) {
            order.setState(SimOrderState.Invalid);
            order.setErrorReason(e+" 不交易, 无行情数据");
            return;
        }
        //检查是否在价格最高最低范围内
        if ( order.getLimitPrice()<lastMd.lowerLimitPrice || order.getLimitPrice()>lastMd.upperLimitPrice ) {
            order.setState(SimOrderState.Invalid);
            order.setErrorReason(PriceUtil.long2str(order.getLimitPrice())+" 超出报价范围");
            return;
        }
        //检查报单价格满足priceTick需求
        long priceTick = feeEvaluator.getPriceTick(e);
        if ( (order.getLimitPrice()%priceTick)!=0 ) {
            order.setState(SimOrderState.Invalid);
            order.setErrorReason(PriceUtil.long2str(order.getLimitPrice())+" 报价TICK不对");
            return;
        }
        //检查保证金需求
        long[] values = feeEvaluator.compute(e, order.getVolume(), (order.getLimitPrice()), order.getDirection(), order.getOffsetFlag());
        long frozenMargin = values[0];
        long frozenCommissions = values[1];

        if ( money[AccMoney_Available]<(frozenMargin+frozenCommissions+PriceUtil.price2long(10.00)) ) {
            order.setState(SimOrderState.Invalid);
            order.setErrorReason("资金不足: "+PriceUtil.long2str(money[AccMoney_Available]));
            return;
        }
        //平仓报单检查持仓
        if ( order.getOffsetFlag()!=OrderOffsetFlag.OPEN ) {
            int posAvail = 0;
            SimPosition pos = positions.get(e);
            if ( pos!=null ) {
                if ( order.getDirection()==OrderDirection.Sell ) {
                    //卖出平多
                    posAvail = pos.getVolume(PosVolume_LongPosition) - pos.getVolume(PosVolume_LongFrozen);
                }else {
                    //买入平空
                    posAvail = pos.getVolume(PosVolume_ShortPosition) - pos.getVolume(PosVolume_ShortFrozen);
                }
            }
            if ( posAvail<order.getVolume() ) {
                order.setState(SimOrderState.Invalid);
                order.setErrorReason("仓位不足: "+order.getVolume());
                return;
            }
        }
        order.setState(SimOrderState.Placed);
    }

    /**
     * 更新账户的可用资金等
     */
    private void updateAccount() {
        long totalUseMargins = 0, totalFrozenMargins=0, totalFrozenCommission=0, totalPosProfit=0, totalCommission=0, totalCloseProfit=0;
        for(SimPosition p:this.positions.values()) {
            totalUseMargins += p.getMoney(PosMoney_UseMargin);
            totalFrozenMargins += p.getMoney(PosMoney_FrozenMargin);
            totalFrozenCommission += p.getMoney(PosMoney_FrozenCommission);
            totalPosProfit += p.getMoney(PosMoney_PositionProfit);
            totalCommission += p.getMoney(PosMoney_Commission);
            totalCloseProfit += p.getMoney(PosMoney_CloseProfit);
        }

        money[AccMoney_Commission] = totalCommission;
        money[AccMoney_CloseProfit] = totalCloseProfit;
        long totalMoney = money[AccMoney_Balance] - money[AccMoney_Commission] + totalPosProfit + money[AccMoney_CloseProfit];

        money[AccMoney_Available] = totalMoney - totalUseMargins - totalFrozenMargins - totalFrozenCommission;
        money[AccMoney_FrozenMargin] = totalFrozenMargins;
        money[AccMoney_CurrMargin] = totalMoney;
        money[AccMoney_FrozenCommission] = totalFrozenCommission;
        money[AccMoney_PositionProfit] = totalPosProfit;
    }

    /**
     * 取消报单
     */
    private void cancelOrder(SimOrder order) {
        order.setState(SimOrderState.Canceled);
    }

    /**
     * 根据最新行情成交报单
     */
    private SimTxn completeOrder(SimOrder order, MarketData md) {
        SimTxn result = null;
        long txnPrice = 0;
        if ( order.getState()==SimOrderState.Placed ) {
            long orderPrice = order.getLimitPrice();
            if ( order.getDirection()==OrderDirection.Buy && orderPrice>=md.lastPrice ) {
                txnPrice = md.lastPrice;
            }
            if (order.getDirection()==OrderDirection.Sell && orderPrice<=md.lastPrice ){
                txnPrice = md.lastPrice;
            }
        }
        if ( txnPrice!=0 ) {
            result = new SimTxn(order, txnPrice, mtService.getMarketTime());
            order.setState(SimOrderState.Completed);
            allTxns.add(result);
        }
        return result;
    }

    /**
     * 将资金从moneyIdx转移到moneyIdx2下, 在扣除保证金时有用.
     * 如果moneyIdx的资金小于amount, 失败.
     */
    boolean transferMoney(int moneyIdx, int moneyIdx2, long amount) {
        if ( money[moneyIdx]<amount ) {
            return false;
        }
        money[moneyIdx] -= amount;
        money[moneyIdx2] += amount;
        return true;
    }

}
