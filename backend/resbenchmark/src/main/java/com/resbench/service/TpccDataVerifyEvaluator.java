package com.resbench.service;

import com.resbench.benchmarks.tpcc.TPCCConfig;
import java.sql.SQLException;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;

/**
 * TPCC 仓数检查里「期望 / 实际 → 结论 + 文案」这一段纯逻辑。
 *
 * <p>刻意与数据库访问分开：不连库就能单测，也避免文案散在 service 里。
 *
 * <p><b>结论口径</b>：{@code warehouse} 行是每个仓的加载线程<b>第一个</b>插进去的
 * （{@code TPCCLoader.java:80}，之后才是 stock → district → customer…），所以"仓数相等"
 * 只证明这些仓<b>开始</b>装了，不证明装完了。因此额外用 {@code district} 行数当完整性旁证，
 * 且文案里不许把 {@link #MATCH} 说成"建仓成功"。
 */
public final class TpccDataVerifyEvaluator {

  /** 实际仓数与配置一致，且 district 行数也对得上 */
  public static final String MATCH = "MATCH";

  /** 实际仓数与配置不一致 */
  public static final String MISMATCH = "MISMATCH";

  /** 仓数一致，但 district 行数少于按实际仓数推算的值 → 疑似上次加载中断 */
  public static final String INCOMPLETE = "INCOMPLETE";

  /** 尚未建仓（表不存在，或表在但一行都没有） */
  public static final String ABSENT = "ABSENT";

  /** 无法判定（连不上 / 没权限 / 读超时 / 配置无效） */
  public static final String UNKNOWN = "UNKNOWN";

  private TpccDataVerifyEvaluator() {}

  /** 完整性对照基准：每仓固定这么多个 district（TPC-C 标准值，读常量不写字面量）。 */
  public static int expectedDistricts(int warehouses) {
    return warehouses * TPCCConfig.configDistPerWhse;
  }

  /** 结论 + 给人看的说明。 */
  public record Evaluation(String conclusion, String message) {}

  /**
   * @param configured           表单里配置的仓数（正整数）
   * @param warehouseTablePresent warehouse 表在当前连接的默认库/模式里存不存在
   * @param actualWarehouses     实际仓数（表不存在时传 0）
   * @param districtTablePresent district 表存不存在
   * @param actualDistricts      实际 district 行数（表不存在时传 0）
   */
  public static Evaluation evaluate(int configured, boolean warehouseTablePresent, int actualWarehouses,
      boolean districtTablePresent, int actualDistricts) {
    if (!warehouseTablePresent || actualWarehouses <= 0) {
      String why = warehouseTablePresent
          ? "warehouse 表存在，但一行都没有"
          : "在当前连接的默认库/模式中没有 warehouse 表";
      return new Evaluation(ABSENT, "尚未建仓：" + why + "（配置仓数 " + configured + "）。");
    }

    int expected = expectedDistricts(actualWarehouses);
    boolean districtShort = !districtTablePresent || actualDistricts < expected;

    if (actualWarehouses != configured) {
      String direction = actualWarehouses > configured
          ? "—— 比配置多，是上一次加载留下的数据；reload 关着时这一轮会直接用这 " + actualWarehouses + " 个仓跑。"
          : "—— 比配置少，上一次加载可能没跑完；reload 关着时这一轮只会有 " + actualWarehouses + " 个仓。";
      String districtNote = districtShort
          ? "另外 district 只有 " + (districtTablePresent ? actualDistricts : 0)
              + " 行，少于按实际仓数推算的 " + expected + " 行。"
          : "";
      return new Evaluation(MISMATCH,
          "实际仓数 " + actualWarehouses + "，与配置的 " + configured + " 不一致" + direction + districtNote);
    }

    if (districtShort) {
      return new Evaluation(INCOMPLETE,
          "仓数与配置一致（" + configured + "），但 district 只有 " + (districtTablePresent ? actualDistricts : 0)
              + " 行、少于按实际仓数推算的 " + expected + " 行"
              + (districtTablePresent ? "" : "（district 表不存在）")
              + " —— 疑似上一次加载中断、数据不全，建议重新加载后再压测。"
              + "注意：warehouse 行是每个仓开始加载时就写入的，仓数相等不代表建仓已完成。");
    }

    String extra = actualDistricts > expected
        ? "另外 district 比预期多 " + (actualDistricts - expected) + " 行，库里可能有额外数据。"
        : "";
    return new Evaluation(MATCH,
        "实际仓数与配置一致（" + configured + "），district 行数 " + actualDistricts
            + " 也符合预期（实际仓数 × " + TPCCConfig.configDistPerWhse + "）。" + extra
            + "注意：仓数一致不代表这一轮会复用 —— reload 开着仍然会重建。");
  }

  /**
   * 是不是"表不存在"。
   *
   * <p>优先看 SQLState：PG 系（含 openGauss / gaussdb）{@code 42P01}、MySQL 系 {@code 42S02}；
   * 再退回 MySQL 的 errorCode {@code 1146}；最后才用 message 兜底。
   *
   * <p>调用方必须先判它、再判权限 —— 缺表的消息里不含权限词，顺序反了会把缺表误判成失败。
   */
  public static boolean isTableMissing(SQLException e, String table) {
    String state = StringUtils.trimToEmpty(e.getSQLState());
    if ("42P01".equals(state) || "42S02".equals(state)) {
      return true;
    }
    if (e.getErrorCode() == 1146) {
      return true;
    }
    String message = StringUtils.defaultString(e.getMessage()).toLowerCase(Locale.ROOT);
    boolean saysMissing = message.contains("does not exist") || message.contains("doesn't exist")
        || message.contains("unknown table") || message.contains("not exist");
    boolean namesTarget = message.contains(StringUtils.defaultString(table).toLowerCase(Locale.ROOT))
        || message.contains("relation") || message.contains("table");
    return saysMissing && namesTarget;
  }

  /** 权限不够。绝不能当成"尚未建仓"报出去 —— 那是把"看不到"说成"没有"。 */
  public static boolean isPermissionDenied(SQLException e) {
    String state = StringUtils.trimToEmpty(e.getSQLState());
    if ("42501".equals(state)) {
      return true;
    }
    int code = e.getErrorCode();
    if (code == 1142 || code == 1044 || code == 1045) {
      return true;
    }
    String message = StringUtils.defaultString(e.getMessage()).toLowerCase(Locale.ROOT);
    return message.contains("permission denied") || message.contains("access denied")
        || message.contains("insufficient privilege") || message.contains("not authorized");
  }
}