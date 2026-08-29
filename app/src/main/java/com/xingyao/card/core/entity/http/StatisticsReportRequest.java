package com.xingyao.card.core.entity.http;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 统计上报请求，对应 POST /api/v1/statistics/report。
 * <p>文档 V4.2 §4.5：设备端统计日数据上报。
 */
public class StatisticsReportRequest {

    /** 统计日期 (yyyy-MM-dd) */
    public String statDate;
    /** 取卡次数 */
    public int takeCount;
    /** 还卡次数 */
    public int returnCount;
    /** 占用卡槽数 */
    public int occupiedCount;
    /** 空闲卡槽数 */
    public int emptyCount;
    /** 故障卡槽数 */
    public int faultCount;
    /** 充电中卡槽数 */
    public int chargingCount;
    /** 已充满卡槽数 */
    public int fullCount;

    public StatisticsReportRequest() {}

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("statDate", statDate);
        json.put("takeCount", takeCount);
        json.put("returnCount", returnCount);
        json.put("occupiedCount", occupiedCount);
        json.put("emptyCount", emptyCount);
        json.put("faultCount", faultCount);
        json.put("chargingCount", chargingCount);
        json.put("fullCount", fullCount);
        return json;
    }
}
