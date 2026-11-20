package com.broker.frontrun;

import java.util.*;

/**
 * 模拟器:生成一个交易日的行情、委托、成交、账户关系与信息接触日志。
 *
 * 埋入的抢先交易手法(ground truth):
 *  S1 配偶账户同股抢跑(客户大单前 30 秒)
 *  S2 多账户分散抢跑(配偶+朋友+共用银行卡,3 个账户,提前 1~4 分钟)
 *  S3 拉长时间间隔(提前 22 分钟)
 *  S4 改用相关期权(提前 2 分钟买入标的看涨期权)
 *  S5 改用同板块股票(提前 5 分钟买板块内另一只票)
 *  D1 诱饵:员工亲属交易了同一只股票,但该员工并未接触此单(不应报警)
 *
 * 背景噪音:散户随机交易、量化账户高频双向交易、跟风盘在大单"之后"追入。
 */
class Simulator {
    final Random rnd = new Random(42);

    final Market market = new Market();
    final List<Employee> employees = new ArrayList<>();
    final List<Account> accounts = new ArrayList<>();
    final List<Relation> relations = new ArrayList<>();
    final List<Order> orders = new ArrayList<>();
    final List<Trade> trades = new ArrayList<>();
    final List<InfoAccess> accesses = new ArrayList<>();
    final Map<String, double[]> accountHistory = new HashMap<>(); // 账户历史单笔金额分布
    final Map<String, String> groundTruth = new LinkedHashMap<>(); // tradeId -> 场景
    final List<String> scenarioLog = new ArrayList<>();

    static final long MIN = 60_000L;
    static final long T0 = 9 * 3600_000L + 30 * MIN; // 09:30
    static final long T_END = 15 * 3600_000L;        // 15:00

    private int orderSeq = 0, tradeSeq = 0;

    // ---- 待回放事件:先全部生成,再按时间排序回放定价 ----
    private record Evt(long ts, String accountId, String instId, Side side,
                       double notional, boolean clientOrder, String scenario) {}
    private final List<Evt> events = new ArrayList<>();
    private final Map<Long, Double> impacts = new HashMap<>(); // 大单时间 -> 冲击幅度(按事件逐个处理,见回放)

    void simulate() {
        buildUniverse();
        buildPeople();
        List<long[]> largeOrderPlan = planLargeOrders(); // [ts, instIdx] 不便,直接内部记录
        generateNoise();
        replay();
    }

    // ---------- 市场与品种 ----------
    private void buildUniverse() {
        String[] sectors = {"银行", "科技", "医药", "新能源"};
        String[][] names = {
                {"华安银行", "中南银行", "联合银行", "金穗银行"},
                {"星宇科技", "云驰智能", "蓝桥软件", "芯辰半导体"},
                {"康泰医药", "百济生物", "恒瑞医疗", "安科基因"},
                {"绿能动力", "天合新能", "风行者", "氢蓝未来"}};
        int code = 600001;
        for (int s = 0; s < sectors.length; s++)
            for (int i = 0; i < 4; i++)
                market.addStock("" + (code++), names[s][i], sectors[s], 10 + rnd.nextDouble() * 40);
        // 每个板块第一只股票发行看涨期权
        for (String u : List.of("600001", "600005", "600009", "600013"))
            market.addOption("OPT_" + u + "_C", market.instruments.get(u).name() + "购12月", u);
    }

    // ---------- 人员、账户、关系 ----------
    private void buildPeople() {
        employees.add(new Employee("E01", "李某", "交易部"));
        employees.add(new Employee("E02", "王某", "交易部"));
        employees.add(new Employee("E03", "陈某", "机构销售"));
        employees.add(new Employee("E04", "赵某", "交易部"));
        employees.add(new Employee("E05", "孙某", "合规部"));
        employees.add(new Employee("E06", "周某", "机构销售"));
        employees.add(new Employee("E07", "吴某", "交易部"));
        employees.add(new Employee("E08", "郑某", "研究部"));

        // 员工亲属/关联账户
        addRel("E01", "A101", "黄某", RelType.SPOUSE);
        addRel("E01", "A102", "李某父", RelType.PARENT);
        addRel("E02", "A103", "王某表弟", RelType.SHARED_DEVICE);
        addRel("E03", "A104", "陈某妻", RelType.SPOUSE);
        addRel("E03", "A105", "陈某友", RelType.FRIEND);
        addRel("E03", "A106", "陈某岳母", RelType.SHARED_BANK);
        addRel("E04", "A107", "赵某母", RelType.PARENT);
        addRel("E05", "A108", "孙某配偶", RelType.SPOUSE); // 诱饵账户:正常交易

        // 机构客户账户
        for (int i = 1; i <= 5; i++)
            accounts.add(new Account("INST0" + i, "机构客户" + i, false));

        // 散户
        for (int i = 1; i <= 60; i++) {
            String id = "R" + (100 + i);
            accounts.add(new Account(id, "散户" + i, false));
            accountHistory.put(id, lognormalSamples(20_000, 0.6, 30));
        }
        // 量化账户:高频、双向、金额中等
        for (int i = 1; i <= 5; i++) {
            String id = "Q" + i;
            accounts.add(new Account(id, "量化私募" + i, false));
            accountHistory.put(id, lognormalSamples(150_000, 0.4, 30));
        }
        // 跟风账户
        for (int i = 1; i <= 6; i++) {
            String id = "M" + i;
            accounts.add(new Account(id, "趋势跟风" + i, false));
            accountHistory.put(id, lognormalSamples(80_000, 0.5, 30));
        }
        // 关联账户的历史交易都很小额(为规模异常检测铺垫)
        for (String id : List.of("A101", "A102", "A103", "A104", "A105", "A106", "A107", "A108"))
            accountHistory.put(id, lognormalSamples(25_000, 0.5, 30));
    }

    private void addRel(String empId, String accId, String holder, RelType type) {
        accounts.add(new Account(accId, holder, false));
        relations.add(new Relation(empId, accId, type));
    }

    private double[] lognormalSamples(double median, double sigma, int n) {
        double[] a = new double[n];
        for (int i = 0; i < n; i++)
            a[i] = median * Math.exp(sigma * rnd.nextGaussian());
        return a;
    }

    // ---------- 客户大单计划 + 场景注入 ----------
    private List<long[]> planLargeOrders() {
        // 12 笔客户大额买入委托,随机分布在交易时段
        String[] pool = {"600001", "600003", "600005", "600009", "600006", "600007",
                "600010", "600012", "600013", "600014", "600015", "600002"};
        List<long[]> plan = new ArrayList<>();
        long span = T_END - T0 - 60 * MIN;
        for (int i = 0; i < 12; i++) {
            long ts = T0 + 30 * MIN + (long) (rnd.nextDouble() * span);
            String inst = pool[i];
            double notional = 8_000_000 + rnd.nextDouble() * 22_000_000;
            String client = "INST0" + (1 + rnd.nextInt(5));
            events.add(new Evt(ts, client, inst, Side.BUY, notional, true, null));
            plan.add(new long[]{ts, i});
            scheduleAccessAndMomentum(ts, inst, i);
        }
        return plan;
    }

    /** 为每笔大单生成信息接触日志;对场景单注入抢先交易;大单之后生成跟风盘 */
    private void scheduleAccessAndMomentum(long ts, String inst, int idx) {
        // 场景注入:idx 0..5 分别对应 S1..S5 与诱饵 D1
        switch (idx) {
            case 0 -> { // S1:配偶同股,提前 30 秒
                access("E01", ts);
                access("E07", ts);
                events.add(new Evt(ts - 30_000, "A101", inst, Side.BUY, 800_000, false, "S1"));
                scenarioLog.add("S1 员工E01(李某)配偶账户A101,客户单前30秒同股买入80万");
            }
            case 1 -> { // S2:三账户分散,提前 1~4 分钟
                access("E03", ts);
                access("E02", ts);
                events.add(new Evt(ts - 1 * MIN - 20_000, "A104", inst, Side.BUY, 600_000, false, "S2"));
                events.add(new Evt(ts - 2 * MIN - 40_000, "A105", inst, Side.BUY, 600_000, false, "S2"));
                events.add(new Evt(ts - 3 * MIN - 50_000, "A106", inst, Side.BUY, 600_000, false, "S2"));
                scenarioLog.add("S2 员工E03(陈某)三个关联账户分散买入,客户单前1~4分钟各60万");
            }
            case 2 -> { // S3:拉长间隔,提前 22 分钟
                access("E04", ts);
                access("E08", ts);
                events.add(new Evt(ts - 22 * MIN, "A107", inst, Side.BUY, 500_000, false, "S3"));
                scenarioLog.add("S3 员工E04(赵某)母亲账户,客户单前22分钟买入50万");
            }
            case 3 -> { // S4:改用期权,提前 2 分钟
                access("E02", ts);
                access("E06", ts);
                events.add(new Evt(ts - 2 * MIN, "A103", "OPT_" + inst + "_C", Side.BUY, 400_000, false, "S4"));
                scenarioLog.add("S4 员工E02(王某)共用设备账户,客户单前2分钟买入标的看涨期权40万");
            }
            case 4 -> { // S5:改用同板块股票,提前 5 分钟
                access("E01", ts);
                access("E05", ts);
                String peer = peerOf(inst);
                events.add(new Evt(ts - 5 * MIN, "A101", peer, Side.BUY, 700_000, false, "S5"));
                scenarioLog.add("S5 员工E01配偶账户改买同板块股票" + peer + ",客户单前5分钟70万");
            }
            case 5 -> { // D1 诱饵:亲属交易但员工无信息接触
                access("E06", ts);
                access("E07", ts);
                events.add(new Evt(ts - 8 * MIN, "A108", inst, Side.BUY, 300_000, false, "D1"));
                scenarioLog.add("D1 诱饵:员工E05(孙某)配偶账户提前8分钟买入同股,但E05未接触该客户单(不应报警)");
            }
            default -> { // 干净大单:随机 2~3 名员工接触
                access(employees.get(rnd.nextInt(employees.size())).id(), ts);
                access(employees.get(rnd.nextInt(employees.size())).id(), ts);
            }
        }
        // 跟风盘:大单成交"之后"1~5 分钟追入(正常行为,不应报警)
        int followers = 3 + rnd.nextInt(4);
        for (int i = 0; i < followers; i++) {
            long fts = ts + (1 + rnd.nextInt(5)) * MIN + rnd.nextInt(60) * 1000L;
            events.add(new Evt(fts, "M" + (1 + rnd.nextInt(6)), inst, Side.BUY,
                    60_000 + rnd.nextDouble() * 120_000, false, null));
        }
    }

    private void access(String empId, long orderTs) {
        // 员工在大单到达前 1~10 分钟查看/接收该委托信息(先占位,回放时补订单号)
        pendingAccess.add(new Object[]{empId, orderTs});
    }

    private final List<Object[]> pendingAccess = new ArrayList<>();

    private String peerOf(String inst) {
        String sector = market.instruments.get(inst).sector();
        for (Instrument i : market.instruments.values())
            if (!i.isOption() && i.sector().equals(sector) && !i.id().equals(inst))
                return i.id();
        return inst;
    }

    // ---------- 背景噪音 ----------
    private void generateNoise() {
        List<String> stockIds = market.instruments.values().stream()
                .filter(i -> !i.isOption()).map(Instrument::id).toList();
        // 散户:全天随机小额交易
        for (int i = 1; i <= 60; i++) {
            String acc = "R" + (100 + i);
            int n = 8 + rnd.nextInt(15);
            for (int k = 0; k < n; k++) {
                long ts = T0 + (long) (rnd.nextDouble() * (T_END - T0));
                String inst = stockIds.get(rnd.nextInt(stockIds.size()));
                Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
                events.add(new Evt(ts, acc, inst, side, 5_000 + rnd.nextDouble() * 55_000, false, null));
            }
        }
        // 量化:高频双向,部分会"恰好"落在大单前的窗口里(考验关联关系过滤)
        for (int q = 1; q <= 5; q++) {
            String acc = "Q" + q;
            for (int k = 0; k < 150; k++) {
                long ts = T0 + (long) (rnd.nextDouble() * (T_END - T0));
                String inst = stockIds.get(rnd.nextInt(stockIds.size()));
                Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
                events.add(new Evt(ts, acc, inst, side, 50_000 + rnd.nextDouble() * 250_000, false, null));
            }
        }
    }

    // ---------- 回放:定价、生成委托/成交、应用冲击 ----------
    private void replay() {
        events.sort(Comparator.comparingLong(Evt::ts));
        Map<Long, List<Evt>> clientOrdersByTs = new HashMap<>();
        for (Evt e : events) {
            market.randomWalk(rnd, e.ts());
            double price = market.get(e.instId());
            long qty = Math.max(100, Math.round(e.notional() / price / 100) * 100);
            String oid = "O" + (++orderSeq);
            String tid = "T" + (++tradeSeq);
            orders.add(new Order(oid, e.accountId(), e.instId(), e.side(), qty, price, e.ts(), e.clientOrder()));
            trades.add(new Trade(tid, oid, e.accountId(), e.instId(), e.side(), qty, price, e.ts()));
            if (e.scenario() != null && !e.scenario().equals("D1"))
                groundTruth.put(tid, e.scenario());
            if (e.clientOrder()) {
                // 大单冲击:金额越大冲击越强,约 0.8%~2%
                double pct = 0.004 + e.notional() / 2e9;
                market.impact(e.instId(), pct, e.ts());
                clientOrdersByTs.computeIfAbsent(e.ts(), k -> new ArrayList<>()).add(e);
                // 补信息接触日志(绑定订单号)
                for (Object[] pa : pendingAccess) {
                    if ((long) pa[1] == e.ts()) {
                        long ats = e.ts() - (1 + rnd.nextInt(10)) * MIN;
                        accesses.add(new InfoAccess((String) pa[0], oid, ats, "查看客户委托"));
                    }
                }
                pendingAccess.removeIf(pa -> (long) pa[1] == e.ts());
            }
        }
    }

    // ---------- 查询辅助 ----------
    String accountHolder(String id) {
        return accounts.stream().filter(a -> a.id().equals(id)).findFirst()
                .map(Account::holder).orElse(id);
    }

    String employeeName(String id) {
        return employees.stream().filter(e -> e.id().equals(id)).findFirst()
                .map(Employee::name).orElse(id);
    }
}
