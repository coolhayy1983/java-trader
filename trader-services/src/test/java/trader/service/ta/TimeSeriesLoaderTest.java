package trader.service.ta;

import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;

import org.junit.Before;
import org.junit.Test;
import org.ta4j.core.TimeSeries;

import trader.common.exchangeable.Exchangeable;
import trader.common.exchangeable.ExchangeableData;
import trader.common.exchangeable.ExchangeableTradingTimes;
import trader.common.tick.PriceLevel;
import trader.common.util.TraderHomeUtil;
import trader.service.TraderHomeHelper;
import trader.service.md.MarketDataService;
import trader.simulator.SimBeansContainer;
import trader.simulator.SimMarketDataService;

public class TimeSeriesLoaderTest {

    @Before
    public void setup() {
        TraderHomeHelper.init();
    }

    @Test
    public void testBarBeginEndTime() {
        Exchangeable ru1901 = Exchangeable.fromString("ru1901");
        {
            LocalDateTime time = LocalDateTime.of(2018, Month.OCTOBER, 11, 10, 31);
            LocalDateTime time2 = LocalDateTime.of(2018, Month.OCTOBER, 11, 10, 25);

            ExchangeableTradingTimes tradingTimes = ru1901.exchange().detectTradingTimes(ru1901, time);
            int barIndex = TimeSeriesLoader.getBarIndex(tradingTimes, PriceLevel.MIN5, time);
            int barIndex2 = TimeSeriesLoader.getBarIndex(tradingTimes, PriceLevel.MIN5, time2);
            assertTrue(barIndex==barIndex2);
        }
        {
            LocalDateTime time = LocalDateTime.of(2018, Month.OCTOBER, 11, 10, 14);
            ExchangeableTradingTimes tradingTimes = ru1901.exchange().detectTradingTimes(ru1901, time);
            LocalDateTime[] barTimes = TimeSeriesLoader.getBarTimes(tradingTimes, PriceLevel.MIN5, -1, time);
            assertTrue( barTimes[0].getMinute()==10 );
            assertTrue( barTimes[1].getMinute()==15 );
        }
        {
            LocalDateTime time = LocalDateTime.of(2018, Month.OCTOBER, 11, 10, 15);
            ExchangeableTradingTimes tradingTimes = ru1901.exchange().detectTradingTimes(ru1901, time);
            LocalDateTime[] barTimes = TimeSeriesLoader.getBarTimes(tradingTimes, PriceLevel.MIN5, -1, time);
            assertTrue( barTimes[0].getMinute()==10 );
            assertTrue( barTimes[1].getMinute()==15 );
        }
        {
            LocalDateTime time = LocalDateTime.of(2018, Month.OCTOBER, 11, 10, 31);
            ExchangeableTradingTimes tradingTimes = ru1901.exchange().detectTradingTimes(ru1901, time);

            LocalDateTime[] barTimes = TimeSeriesLoader.getBarTimes(tradingTimes, PriceLevel.MIN5, -1, time);
            assertTrue( barTimes[0].getMinute()==30 );
            assertTrue( barTimes[1].getMinute()==35 );
        }
        {
            LocalDateTime time = LocalDateTime.of(2018, Month.OCTOBER, 11, 13, 31);
            ExchangeableTradingTimes tradingTimes = ru1901.exchange().detectTradingTimes(ru1901, time);
            LocalDateTime[] barTimes = TimeSeriesLoader.getBarTimes(tradingTimes, PriceLevel.MIN15, -1, time);
            assertTrue( barTimes[0].getMinute()==30 );
            assertTrue( barTimes[1].getMinute()==45 );
        }
    }

    @Test
    public void testBarIndex_au1906() {
        Exchangeable au1906 = Exchangeable.fromString("au1906");
        LocalDateTime time = LocalDateTime.of(2018, Month.DECEMBER, 13, 02, 29);
        ExchangeableTradingTimes tradingTimes = au1906.exchange().detectTradingTimes(au1906, time);

        LocalDateTime time2 = LocalDateTime.of(2018, Month.DECEMBER, 13, 02, 30);
        int barIndex2 = TimeSeriesLoader.getBarIndex(tradingTimes, PriceLevel.MIN1, time2);
        int barIndex = TimeSeriesLoader.getBarIndex(tradingTimes, PriceLevel.MIN1, time);

        LocalDateTime time3 = LocalDateTime.of(2018, Month.DECEMBER, 13, 02, 30, 00).withNano(500*1000000);
        int barIndex3 = TimeSeriesLoader.getBarIndex(tradingTimes, PriceLevel.MIN1, time3);
        assertTrue(barIndex==barIndex3);

        assertTrue(barIndex==barIndex2);
    }

    @Test
    public void testCtpTick() throws Exception
    {
        SimBeansContainer beansContainer = new SimBeansContainer();
        final SimMarketDataService mdService = new SimMarketDataService();
        mdService.init(beansContainer);
        beansContainer.addBean(MarketDataService.class, mdService);

        ExchangeableData data = TraderHomeUtil.getExchangeableData();
        TimeSeriesLoader loader= new TimeSeriesLoader(beansContainer, data);
        loader
            .setExchangeable(Exchangeable.fromString("ru1901"))
            .setStartTradingDay(LocalDate.of(2018, 12, 03))
            .setEndTradingDay(LocalDate.of(2018, 12, 03))
            .setLevel(PriceLevel.MIN1);

        TimeSeries min1Series = loader.load();
        assertTrue(min1Series.getBarCount()>0);

    }

    @Test
    public void testMinFromCtpTick() throws Exception
    {
        SimBeansContainer beansContainer = new SimBeansContainer();
        final SimMarketDataService mdService = new SimMarketDataService();
        mdService.init(beansContainer);
        beansContainer.addBean(MarketDataService.class, mdService);

        ExchangeableData data = TraderHomeUtil.getExchangeableData();
        TimeSeriesLoader loader= new TimeSeriesLoader(beansContainer, data);
        loader
            .setExchangeable(Exchangeable.fromString("ru1901"))
            .setStartTradingDay(LocalDate.of(2018, 12, 3))
            .setEndTradingDay(LocalDate.of(2018, 12, 03))
            .setLevel(PriceLevel.MIN1);

        TimeSeries min1Series = loader.load();
        assertTrue(min1Series.getBarCount()>0);

        loader.setLevel(PriceLevel.MIN3);
        TimeSeries min3Series = loader.load();
        assertTrue(min3Series.getBarCount()>0);

        assertTrue((min1Series.getBarCount()+2)/3==min3Series.getBarCount());

        loader.setLevel(PriceLevel.MIN5);
        TimeSeries min5Series = loader.load();
        assertTrue(min5Series.getBarCount()>0);

        assertTrue((min1Series.getBarCount())/5==min5Series.getBarCount());
    }


    @Test
    public void testVolFromCtpTick() throws Exception
    {
        SimBeansContainer beansContainer = new SimBeansContainer();
        final SimMarketDataService mdService = new SimMarketDataService();
        mdService.init(beansContainer);
        beansContainer.addBean(MarketDataService.class, mdService);

        ExchangeableData data = TraderHomeUtil.getExchangeableData();
        TimeSeriesLoader loader= new TimeSeriesLoader(beansContainer, data);
        loader
            .setExchangeable(Exchangeable.fromString("ru1901"))
            .setStartTradingDay(LocalDate.of(2018, 12, 3))
            .setEndTradingDay(LocalDate.of(2018, 12, 03))
            .setLevel(PriceLevel.VOL1K);

        TimeSeries min1Series = loader.load();
        assertTrue(min1Series.getBarCount()>0);
    }

    @Test
    public void testMinFromCtpTick_au1906() throws Exception
    {
        SimBeansContainer beansContainer = new SimBeansContainer();
        final SimMarketDataService mdService = new SimMarketDataService();
        mdService.init(beansContainer);
        beansContainer.addBean(MarketDataService.class, mdService);

        ExchangeableData data = TraderHomeUtil.getExchangeableData();
        TimeSeriesLoader loader= new TimeSeriesLoader(beansContainer, data);
        loader
            .setExchangeable(Exchangeable.fromString("au1906"))
            .setStartTradingDay(LocalDate.of(2018, 12, 13))
            .setEndTradingDay(LocalDate.of(2018, 12, 13))
            .setLevel(PriceLevel.MIN1);

        TimeSeries min1Series = loader.load();
        assertTrue(min1Series.getBarCount()>0);

        loader.setLevel(PriceLevel.MIN3);
        TimeSeries min3Series = loader.load();
        assertTrue(min3Series.getBarCount()>0);

        assertTrue((min1Series.getBarCount()+2)/3==min3Series.getBarCount());

        loader.setLevel(PriceLevel.MIN5);
        TimeSeries min5Series = loader.load();
        assertTrue(min5Series.getBarCount()>0);

        assertTrue((min1Series.getBarCount())/5==min5Series.getBarCount());
    }

    @Test
    public void testMinFromMin1() throws Exception
    {
        SimBeansContainer beansContainer = new SimBeansContainer();
        final SimMarketDataService mdService = new SimMarketDataService();
        mdService.init(beansContainer);
        beansContainer.addBean(MarketDataService.class, mdService);

        ExchangeableData data = TraderHomeUtil.getExchangeableData();
        TimeSeriesLoader loader= new TimeSeriesLoader(beansContainer, data);
        loader
            .setExchangeable(Exchangeable.fromString("ru1901"))
            .setStartTradingDay(LocalDate.of(2018, 12, 03))
            .setEndTradingDay(LocalDate.of(2018, 12, 03))
            .setLevel(PriceLevel.MIN1);

        TimeSeries min1Series = loader.load();
        assertTrue(min1Series.getBarCount()>0);

        loader.setLevel(PriceLevel.MIN3);
        TimeSeries min3Series = loader.load();
        assertTrue(min3Series.getBarCount()>0);

        assertTrue((min1Series.getBarCount()+2)/3==min3Series.getBarCount());

        loader.setLevel(PriceLevel.MIN5);
        TimeSeries min5Series = loader.load();
        assertTrue(min5Series.getBarCount()>0);

        assertTrue((min1Series.getBarCount())/5==min5Series.getBarCount());
    }

}
