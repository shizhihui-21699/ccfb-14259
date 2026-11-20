package com.broker.frontrun;

import java.util.*;

/**
 * 行情引擎:
 *  - 股票价格随机游走;
 *  - 客户大单成交后对标的产生瞬时冲击(价格跳空),同板块股票按 30% 联动;
 *  - 看涨期权价格按固定杠杆跟随标的涨跌。
 * 历史价格按事件发生时间记录,支持按时间回查(用于获利计算)。
 */
class Market {
    private static final double OPT_LEVERAGE = 8.0;   // 期权杠杆
    private static final double SECTOR_SYMPATHY = 0.3; // 板块联动系数

    final Map<String, Instrument> instruments = new LinkedHashMap<>();
    private final Map<String, Double> price = new HashMap<>();
    private final Map<String, TreeMap<Long, Double>> history = new HashMap<>();
    private final Map<String, List<String>> optionsByUnderlying = new HashMap<>();
    private final Map<String, List<String>> stocksBySector = new HashMap<>();

    void addStock(String id, String name, String sector, double base) {
        instruments.put(id, new Instrument(id, name, InstrumentType.STOCK, sector, null, base));
        price.put(id, base);
        stocksBySector.computeIfAbsent(sector, k -> new ArrayList<>()).add(id);
    }

    void addOption(String id, String name, String underlyingId) {
        Instrument u = instruments.get(underlyingId);
        double base = u.basePrice() * 0.05; // 简化:期权名义价约为标的 5%
        instruments.put(id, new Instrument(id, name, InstrumentType.CALL_OPTION,
                u.sector(), underlyingId, base));
        price.put(id, base);
        optionsByUnderlying.computeIfAbsent(underlyingId, k -> new ArrayList<>()).add(id);
    }

    double get(String id) { return price.get(id); }

    /** 回查某时刻价格(取不晚于 ts 的最近记录) */
    double at(String id, long ts) {
        TreeMap<Long, Double> h = history.get(id);
        if (h == null) return instruments.get(id).basePrice();
        Map.Entry<Long, Double> e = h.floorEntry(ts);
        return e != null ? e.getValue() : instruments.get(id).basePrice();
    }

    private void set(String id, double p, long ts) {
        price.put(id, p);
        history.computeIfAbsent(id, k -> new TreeMap<>()).put(ts, p);
    }

    /** 随机游走一步(每个市场事件前调用) */
    void randomWalk(Random rnd, long ts) {
        for (Instrument inst : instruments.values()) {
            if (inst.isOption()) continue;
            set(inst.id(), price.get(inst.id()) * (1 + rnd.nextGaussian() * 0.0008), ts);
        }
        syncOptions(ts);
    }

    /** 大单冲击:标的跳空 pct,同板块联动 0.3*pct */
    void impact(String stockId, double pct, long ts) {
        set(stockId, price.get(stockId) * (1 + pct), ts);
        String sector = instruments.get(stockId).sector();
        for (String peer : stocksBySector.getOrDefault(sector, List.of())) {
            if (!peer.equals(stockId))
                set(peer, price.get(peer) * (1 + SECTOR_SYMPATHY * pct), ts);
        }
        syncOptions(ts);
    }

    private void syncOptions(long ts) {
        for (var e : optionsByUnderlying.entrySet()) {
            Instrument u = instruments.get(e.getKey());
            double uRet = price.get(u.id()) / u.basePrice() - 1;
            for (String optId : e.getValue()) {
                Instrument o = instruments.get(optId);
                double p = o.basePrice() * Math.max(0.1, 1 + OPT_LEVERAGE * uRet);
                set(optId, p, ts);
            }
        }
    }

    /** 品种相关性:同标的 1.0;标的的期权 0.8;同板块股票 0.5;其余 0 */
    double relatedness(String a, String b) {
        if (a.equals(b)) return 1.0;
        Instrument ia = instruments.get(a), ib = instruments.get(b);
        if (ia.underlyingId() != null && ia.underlyingId().equals(b)) return 0.8;
        if (ib.underlyingId() != null && ib.underlyingId().equals(a)) return 0.8;
        if (!ia.isOption() && !ib.isOption() && ia.sector().equals(ib.sector())) return 0.5;
        return 0.0;
    }
}
