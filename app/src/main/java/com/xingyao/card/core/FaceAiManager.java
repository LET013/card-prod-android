package com.xingyao.card.core;

import android.content.Context;
import android.graphics.Bitmap;

import com.ai.face.core.engine.FaceAISDKEngine;
import com.ai.face.faceSearch.search.FaceSearchFeature;
import com.ai.face.faceSearch.search.FaceSearchFeatureManger;

import java.util.List;

/**
 * FaceAISDK 管理器 - 封装 FaceAISDK (com.ai.face.*) 的初始化和人脸库操作.
 *
 * CameraX + 人脸检测/搜索由 FaceEnrollmentController 直接负责.
 * 本类仅管理:
 *   - 引擎生命周期 (initialize, release)
 *   - 人脸库 CRUD (insert, delete, list, count)
 *   - 特征向量转换 (bitmap → feature string)
 */
public class FaceAiManager {

    private static volatile FaceAiManager instance;
    private Context appContext;
    private boolean initialized = false;

    private FaceAiManager() {}

    public static FaceAiManager getInstance() {
        if (instance == null) {
            synchronized (FaceAiManager.class) {
                if (instance == null) {
                    instance = new FaceAiManager();
                }
            }
        }
        return instance;
    }

    /**
     * 初始化 SDK 引擎. 应在 Application.onCreate 或首次使用前调用.
     */
    public void init(Context context) {
        if (initialized) return;
        this.appContext = context.getApplicationContext();
        // 触发引擎初始化 (内部会加载 TF Lite 模型和 bin 文件)
        FaceAISDKEngine.getInstance(appContext);
        initialized = true;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 释放引擎资源.
     */
    public void release() {
        if (initialized) {
            FaceAISDKEngine.getInstance(appContext).release();
            initialized = false;
        }
    }

    // ==================== 人脸库 CRUD ====================

    /**
     * 向人脸库插入人脸特征 (用于 1:N 搜索).
     *
     * @param faceID   用户唯一标识 (如员工ID)
     * @param feature  人脸特征字符串 (Base64 编码的 1024 维向量)
     * @param tag      标签 (可选)
     * @param group    分组 (可选)
     */
    public void insertFaceFeature(String faceID, String feature, String tag, String group) {
        FaceSearchFeatureManger.getInstance(appContext)
                .insertFaceFeature(faceID, feature, System.currentTimeMillis(), tag, group);
    }

    /**
     * 删除指定 faceID 的人脸.
     */
    public void deleteFaceFeature(String faceID) {
        FaceSearchFeatureManger.getInstance(appContext).deleteFaceFaceFeature(faceID);
    }

    /**
     * 清空所有人脸.
     */
    public void clearAllFaces() {
        FaceSearchFeatureManger.getInstance(appContext).clearAllFaceFaceFeature();
    }

    /**
     * 获取已注册的人脸数量.
     */
    public int getFaceCount() {
        return FaceSearchFeatureManger.getInstance(appContext).getFaceSearchLibCount();
    }

    /**
     * 查询所有人脸.
     */
    public List<FaceSearchFeature> listAllFaces() {
        return FaceSearchFeatureManger.getInstance(appContext).queryAllFaceFaceFeature();
    }

    /**
     * 根据 faceID 查询人脸特征.
     */
    public FaceSearchFeature queryFaceByID(String faceID) {
        return FaceSearchFeatureManger.getInstance(appContext).queryFaceFeatureByID(faceID);
    }

    // ==================== 特征转换 ====================

    /**
     * 从裁剪后的人脸 Bitmap 提取 1024 维特征 (Base64 字符串).
     */
    public String extractFaceFeature(Bitmap croppedFaceBitmap) {
        return FaceAISDKEngine.getInstance(appContext).croppedBitmap2Feature(croppedFaceBitmap);
    }

    /**
     * 保存裁剪后的人脸图片到本地.
     */
    public void saveFaceImage(Bitmap croppedFaceBitmap, String faceID, String dirPath) {
        FaceAISDKEngine.getInstance(appContext).saveCroppedFaceImage(croppedFaceBitmap, faceID, dirPath);
    }
}
