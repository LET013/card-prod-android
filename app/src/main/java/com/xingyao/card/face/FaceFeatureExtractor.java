package com.xingyao.card.face;

import android.graphics.Bitmap;

/**
 * 人脸特征提取器接口，用于将 Bitmap 转换为人脸特征字符串（Base64）。
 */
public interface FaceFeatureExtractor {

    /**
     * 从人脸区域 Bitmap 提取特征。
     *
     * @param croppedBitmap 裁剪后的人脸区域
     * @return 特征字符串（Base64），失败返回 null
     */
    String extract(Bitmap croppedBitmap);
}
