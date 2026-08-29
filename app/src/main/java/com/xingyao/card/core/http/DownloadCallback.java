package com.xingyao.card.core.http;

import java.io.File;

/**
 * 文件下载回调（含进度）。
 * 回调在主线程执行，可直接更新 UI。
 */
public interface DownloadCallback {

    /**
     * 下载进度更新。
     * @param downloaded 已下载字节数
     * @param total       总字节数（可能为 -1 表示未知）
     */
    void onProgress(long downloaded, long total);

    /**
     * 下载成功。
     * @param file 下载完成的目标文件
     */
    void onSuccess(File file);

    /**
     * 下载失败。
     * @param code    HTTP 状态码（非 2xx），或 -1 表示网络/IO 异常
     * @param message 错误描述
     */
    void onFailure(int code, String message);
}
