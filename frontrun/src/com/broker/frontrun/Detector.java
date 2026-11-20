package com.broker.frontrun;

import java.util.*;

/**
 * 关联图谱:员工 -> 账户 的已知关系边。
 * 实际系统中关系数据不完整,这里按"已知关系"工作;
 * 支持按员工集合查询某账户的最强关联(用于证据链)。
 */
class RelationGraph {
    private final Map<String, Map<String, RelType>> byAccount = new HashMap<>();

    void add(Relation r) {
        byAccount.computeIfAbsent(r.accountId(), k -> new HashMap<>())
                .merge(r.employeeId(), r.type(), (a, b) -> a.weight >= b.weight ? a : b);
    }

    /** 账户与指定员工集合中的最强关联;null 表示未发现关联 */
    Map.Entry<String, RelType> strongest(String accountId, Set<String> employeeIds) {
        Map<String, RelType> m = byAccount.get(accountId);
        if (m == null) return null;
        return m.entrySet().stream()
                .filter(e -> employeeIds.contains(e.getKey()))
                .max(Comparator.comparingDouble(e -> e.getValue().weight))
                .orElse(null);
    }
}

/** 一条警报:某账户在某客户大单前的可疑同向交易 + 完整证据链 */
class Alert {
    final Order clientOrder;
    final String accountId;
    final List<Trade> trades;
    final double score;
    final List<String> evidence;

    Alert(Order clientOrder, String accountId, List<Trade> trades, double score, List<String> evidence) {
        this.clientOrder = clientOrder;
        this.accountId = accountId;
        this.trades = trades;
        this.score = score;
        this.evidence = evidence;
    }
}

/**
 * 抢先交易检测器。
 *
 * 思路:以客户大额委托为触发点,向前滑动时间窗扫描同向成交,
 * 对每个命中账户计算六维特征并加权打分:
 *   1. 关联强度 rel     —— 账户与"接触过该单信息"的员工之间的最强关系
 *   2. 品种相关 related —— 同股 1.0 / 标的期权 0.8 / 同板块 0.5
 *   3. 时间接近 prox    —— 距客户单越近越高,30 分钟线性衰减
 *   4. 规模异常 abn     —— 相对该账户历史单笔金额的异常程度
 *   5. 获利情况 profit  —— 客户单推动价格后,该账户的浮动收益方向与幅度
 * 报警门槛:必须存在"信息接触 × 账户关联"链条(rel > 0)且总分 >= 阈值。
 * 无关联但统计异常的交易进入观察名单(供人工排查,不直接定性)。
 */
class Detector {
    private static final long WINDOW = 30 * 60_000L;   // 向前 30 分钟
    private static final double LARGE_ORDER = 5_000_000; // 大额委托门槛(元)
    private static final double ALERT_THRESHOLD = 0.55;

    private static final double W_REL = 0.30, W_RELATED = 0.20, W_PROX = 0.20,
            W_ABN = 0.15, W_PROFIT = 0.15;

    private final Simulator sim;
    private final RelationGraph graph = new RelationGraph();
    private final Map<String, Set<String>> accessByOrder = new HashMap<>();

    final List<Alert> alerts = new ArrayList<>();
    final List<String> watchlist = new ArrayList<>();

    Detector(Simulator sim) {
        this.sim = sim;
        sim.relations.forEach(graph::add);
        for (InfoAccess ia : sim.accesses)
            accessByOrder.computeIfAbsent(ia.orderId(), k -> new HashSet<>()).add(ia.employeeId());
    }

    void run() {
        for (Order o : sim.orders) {
            if (!o.clientOrder() || o.notional() < LARGE_ORDER) continue;
            scan(o);
        }
    }

    private void scan(Order o) {
        Set<String> insiders = accessByOrder.getOrDefault(o.id(), Set.of());
        // 1) 收集窗口内同向、相关品种的成交,按账户归并
        Map<String, List<Trade>> byAccount = new LinkedHashMap<>();
        for (Trade t : sim.trades) {
            if (t.ts() >= o.ts() || t.ts() < o.ts() - WINDOW) continue;
            if (t.side() != o.side()) continue;
            if (t.accountId().startsWith("INST")) continue; // 排除其他机构客户
            if (sim.market.relatedness(t.instrumentId(), o.instrumentId()) <= 0) continue;
            byAccount.computeIfAbsent(t.accountId(), k -> new ArrayList<>()).add(t);
        }
        // 2) 逐账户打分
        for (var e : byAccount.entrySet()) {
            String acc = e.getKey();
            List<Trade> ts = e.getValue();
            var rel = graph.strongest(acc, insiders);
            double relW = rel == null ? 0 : rel.getValue().weight;

            double related = ts.stream().mapToDouble(t -> sim.market.relatedness(t.instrumentId(), o.instrumentId())).max().orElse(0);
            long minGap = ts.stream().mapToLong(t -> o.ts() - t.ts()).min().orElse(WINDOW);
            double prox = 1.0 - (double) minGap / WINDOW;
            double notional = ts.stream().mapToDouble(Trade::notional).sum();
            double abn = abnormality(acc, notional);
            double profit = profitScore(ts, o);

            double score = W_REL * relW + W_RELATED * related + W_PROX * prox
                    + W_ABN * abn + W_PROFIT * profit;

            if (relW > 0 && score >= ALERT_THRESHOLD) {
                alerts.add(new Alert(o, acc, ts, score,
                        buildEvidence(o, acc, ts, rel, related, minGap, notional, abn, profit, score)));
            } else if (relW == 0 && abn >= 0.8 && related >= 1.0) {
                watchlist.add(String.format(
                        "[观察] 账户 %s(%s) 在客户单 %s 前 %d 秒同股%s向交易 %.0f 元,规模异常但未发现与知情员工的关联,建议人工排查",
                        acc, sim.accountHolder(acc), o.id(), minGap / 1000,
                        o.side() == Side.BUY ? "买" : "卖", notional));
            }
        }
    }

    /** 规模异常:本次金额相对账户历史 P95 的倍数,截断到 [0,1] */
    private double abnormality(String acc, double notional) {
        double[] hist = sim.accountHistory.get(acc);
        if (hist == null || hist.length == 0) return 0.5;
        double[] sorted = hist.clone();
        Arrays.sort(sorted);
        double p95 = sorted[(int) (sorted.length * 0.95)];
        return Math.max(0, Math.min(1, notional / (p95 * 2)));
    }

    /** 获利评分:客户单成交 10 分钟后,价格沿交易方向的变动幅度(2% 记满分) */
    private double profitScore(List<Trade> ts, Order o) {
        double best = 0;
        for (Trade t : ts) {
            double after = sim.market.at(t.instrumentId(), o.ts() + 10 * 60_000L);
            double pct = (after - t.price()) / t.price();
            if (t.side() == Side.SELL) pct = -pct;
            best = Math.max(best, Math.min(1, pct / 0.02));
        }
        return Math.max(0, best);
    }

    // ---------- 证据链 ----------
    private List<String> buildEvidence(Order o, String acc, List<Trade> ts,
                                       Map.Entry<String, RelType> rel, double related,
                                       long minGap, double notional, double abn,
                                       double profit, double score) {
        List<String> ev = new ArrayList<>();
        Instrument oi = sim.market.instruments.get(o.instrumentId());
        ev.add(String.format("客户大单:订单 %s,机构账户 %s 于 %s %s %s(%s) %,d 股,金额约 %,.0f 元",
                o.id(), o.accountId(), fmt(o.ts()), o.side() == Side.BUY ? "买入" : "卖出",
                oi.name(), o.instrumentId(), o.qty(), o.notional()));

        Set<String> insiders = accessByOrder.get(o.id());
        for (String emp : insiders) {
            long ats = sim.accesses.stream().filter(a -> a.orderId().equals(o.id()) && a.employeeId().equals(emp))
                    .mapToLong(InfoAccess::ts).min().orElse(o.ts());
            ev.add(String.format("信息接触:员工 %s(%s)于 %s 查看了该客户委托,具备提前知悉条件",
                    emp, sim.employeeName(emp), fmt(ats)));
        }
        ev.add(String.format("关联关系:员工 %s(%s)—[%s]→ 账户 %s(%s),关系强度 %.2f",
                rel.getKey(), sim.employeeName(rel.getKey()), rel.getValue().cn,
                acc, sim.accountHolder(acc), rel.getValue().weight));

        for (Trade t : ts) {
            Instrument ti = sim.market.instruments.get(t.instrumentId());
            String kind = t.instrumentId().equals(o.instrumentId()) ? "同股"
                    : ti.isOption() ? "标的期权" : "同板块股票";
            ev.add(String.format("可疑交易:成交 %s,账户于 %s 买入 %s(%s,%s) %,d 股 @ %.2f,金额 %,.0f 元,领先客户单 %d 秒",
                    t.id(), fmt(t.ts()), ti.name(), t.instrumentId(), kind,
                    t.qty(), t.price(), t.notional(), (o.ts() - t.ts()) / 1000));
        }
        ev.add(String.format("品种相关度 %.2f;时间接近度:最近一笔领先 %d 秒", related, minGap / 1000));
        ev.add(String.format("规模异常:窗口内合计买入 %,.0f 元,约为该账户历史单笔 P95 的 %.1f 倍(异常度 %.2f)",
                notional, abn * 2, abn));
        double after = sim.market.at(ts.get(0).instrumentId(), o.ts() + 10 * 60_000L);
        double pct = (after - ts.get(0).price()) / ts.get(0).price() * 100;
        ev.add(String.format("获利情况:客户单成交后 10 分钟,持仓浮盈约 %.2f%%(获利评分 %.2f)", pct, profit));
        ev.add(String.format("综合评分: %.2f (阈值 %.2f)", score, ALERT_THRESHOLD));
        return ev;
    }

    static String fmt(long ts) {
        long s = ts / 1000;
        return String.format("%02d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60);
    }
}
