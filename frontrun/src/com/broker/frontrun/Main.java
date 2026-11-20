package com.broker.frontrun;

import java.util.*;
import java.util.stream.*;

/**
 * 抢先交易监测系统入口:
 *   1. 模拟一个交易日的行情/委托/成交/关系/信息接触数据(埋入 5 种手法 + 1 个诱饵);
 *   2. 运行检测器,输出警报与证据链;
 *   3. 对照 ground truth 评估召回率与精确率。
 */
public class Main {
    public static void main(String[] args) {
        Simulator sim = new Simulator();
        sim.simulate();

        System.out.println("=".repeat(72));
        System.out.println("抢先交易(老鼠仓)监测系统 —— 模拟运行报告");
        System.out.println("=".repeat(72));
        System.out.printf("数据规模: %d 只品种 | %d 个账户 | %d 名员工 | %d 条关系 | %d 笔委托 | %d 笔成交 | %d 条信息接触%n",
                sim.market.instruments.size(), sim.accounts.size(), sim.employees.size(),
                sim.relations.size(), sim.orders.size(), sim.trades.size(), sim.accesses.size());

        System.out.println("\n--- 埋入的测试场景(ground truth) ---");
        sim.scenarioLog.forEach(s -> System.out.println("  " + s));

        Detector det = new Detector(sim);
        det.run();

        System.out.println("\n--- 警报与证据链(共 " + det.alerts.size() + " 条) ---");
        det.alerts.sort(Comparator.comparingDouble((Alert a) -> a.score).reversed());
        int i = 1;
        for (Alert a : det.alerts) {
            System.out.printf("%n【警报 %d】账户 %s(%s) 涉嫌抢跑客户订单 %s,评分 %.2f%n",
                    i++, a.accountId, sim.accountHolder(a.accountId), a.clientOrder.id(), a.score);
            for (String line : a.evidence)
                System.out.println("    " + line);
        }

        System.out.println("\n--- 观察名单(统计异常但未发现关联,不直接定性) ---");
        if (det.watchlist.isEmpty()) System.out.println("  (无)");
        det.watchlist.forEach(w -> System.out.println("  " + w));

        evaluate(sim, det);
    }

    /** 对照 ground truth 评估:场景级召回 + 交易级精确率/召回率 */
    private static void evaluate(Simulator sim, Detector det) {
        Set<String> alertedTrades = det.alerts.stream()
                .flatMap(a -> a.trades.stream()).map(Trade::id).collect(Collectors.toSet());
        Set<String> gt = sim.groundTruth.keySet();

        Set<String> tp = new HashSet<>(alertedTrades);
        tp.retainAll(gt);
        Set<String> fp = new HashSet<>(alertedTrades);
        fp.removeAll(gt);

        // 场景级:每个埋入场景是否至少有一笔交易被命中
        Map<String, Long> gtByScenario = sim.groundTruth.values().stream()
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()));
        System.out.println("\n--- 检测效果评估 ---");
        int hit = 0;
        for (String sc : gtByScenario.keySet()) {
            boolean found = det.alerts.stream().anyMatch(a -> a.trades.stream()
                    .anyMatch(t -> sc.equals(sim.groundTruth.get(t.id()))));
            System.out.printf("  场景 %s: %s%n", sc, found ? "✔ 已检出" : "✘ 漏报");
            if (found) hit++;
        }
        // 诱饵检查:不得出现在警报中
        boolean decoyAlerted = det.alerts.stream().anyMatch(a -> a.accountId.equals("A108"));
        System.out.printf("  诱饵 D1(无信息接触的亲属交易): %s%n", decoyAlerted ? "✘ 误报" : "✔ 未误报");

        System.out.printf("%n  场景级召回: %d/%d%n", hit, gtByScenario.size());
        System.out.printf("  交易级: 命中(TP)=%d 误报(FP)=%d 漏报(FN)=%d 精确率=%.2f 召回率=%.2f%n",
                tp.size(), fp.size(), gt.size() - tp.size(),
                alertedTrades.isEmpty() ? 1.0 : (double) tp.size() / alertedTrades.size(),
                gt.isEmpty() ? 1.0 : (double) tp.size() / gt.size());
        if (!fp.isEmpty())
            System.out.println("  误报交易: " + fp);
    }
}
