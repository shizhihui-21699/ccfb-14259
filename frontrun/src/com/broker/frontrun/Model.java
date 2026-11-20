package com.broker.frontrun;

/** 证券品种类型 */
enum InstrumentType { STOCK, CALL_OPTION }

/** 买卖方向 */
enum Side { BUY, SELL }

/**
 * 员工与账户之间的关联关系类型。
 * weight 为关系强度(0~1),用于证据链打分;实际系统中来自户籍、开户资料、
 * 设备指纹、银行卡、地址等数据,且往往不完整——本系统按"已知关系"工作。
 */
enum RelType {
    SELF(1.00, "本人"),
    SPOUSE(0.90, "配偶"),
    PARENT(0.85, "父母"),
    CHILD(0.85, "子女"),
    SIBLING(0.80, "兄弟姐妹"),
    SHARED_DEVICE(0.70, "共用设备/MAC"),
    SHARED_BANK(0.65, "共用银行卡"),
    FRIEND(0.60, "朋友"),
    SHARED_ADDRESS(0.50, "同住地址");

    final double weight;
    final String cn;
    RelType(double w, String cn) { this.weight = w; this.cn = cn; }
}

/** 证券品种:股票或以其为标的的看涨期权 */
record Instrument(String id, String name, InstrumentType type, String sector,
                  String underlyingId, double basePrice) {
    boolean isOption() { return type == InstrumentType.CALL_OPTION; }
}

record Account(String id, String holder, boolean employeeAccount) {}

record Employee(String id, String name, String desk) {}

/** 员工 -> 账户 的关联边 */
record Relation(String employeeId, String accountId, RelType type) {}

/** 委托单;clientOrder=true 表示客户(机构)委托,是检测的触发对象 */
record Order(String id, String accountId, String instrumentId, Side side,
             long qty, double price, long ts, boolean clientOrder) {
    double notional() { return qty * price; }
}

/** 成交记录 */
record Trade(String id, String orderId, String accountId, String instrumentId,
             Side side, long qty, double price, long ts) {
    double notional() { return qty * price; }
}

/** 员工接触客户委托信息的记录(查看委托、柜台指派、风控抄送等) */
record InfoAccess(String employeeId, String orderId, long ts, String how) {}
