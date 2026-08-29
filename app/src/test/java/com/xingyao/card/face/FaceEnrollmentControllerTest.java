package com.xingyao.card.face;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FaceEnrollmentControllerTest {

    @Test
    public void faceSdkSearchTimeoutStaysWithinSupportedRange() {
        assertEquals(6000L, FaceEnrollmentController.SDK_SEARCH_TIMEOUT_MS);
    }

    @Test
    public void frontCameraFacing_requiresMirrorForEnrollmentFrames() {
        assertTrue(FaceEnrollmentController.isFrontCameraFacing("front"));
        assertTrue(FaceEnrollmentController.isFrontCameraFacing("FRONT"));
        assertFalse(FaceEnrollmentController.isFrontCameraFacing("back"));
        assertFalse(FaceEnrollmentController.isFrontCameraFacing(null));
    }

    // ── isStableTip ──────────────────────────────────────

    @Test
    public void isStableTip_recognizesAllStableTips() {
        assertTrue(FaceEnrollmentController.isStableTip("保持稳定"));
        assertTrue(FaceEnrollmentController.isStableTip("正在检测中..."));
        assertTrue(FaceEnrollmentController.isStableTip("检测中..."));
        assertTrue(FaceEnrollmentController.isStableTip("请保持不动"));
        assertTrue(FaceEnrollmentController.isStableTip("处理中..."));
    }

    @Test
    public void isStableTip_rejectsNonStableTips() {
        assertFalse(FaceEnrollmentController.isStableTip("请靠近一点"));
        assertFalse(FaceEnrollmentController.isStableTip("请离远一点"));
        assertFalse(FaceEnrollmentController.isStableTip("未检测到人脸"));
        assertFalse(FaceEnrollmentController.isStableTip("请正对摄像头"));
        assertFalse(FaceEnrollmentController.isStableTip("请勿遮挡面部"));
        assertFalse(FaceEnrollmentController.isStableTip("请抬头"));
        assertFalse(FaceEnrollmentController.isStableTip("请稍微低头"));
    }

    @Test
    public void isStableTip_rejectsEmptyAndRandom() {
        assertFalse(FaceEnrollmentController.isStableTip(""));
        assertFalse(FaceEnrollmentController.isStableTip("随机文本"));
    }

    // ── tipForCode ───────────────────────────────────────

    @Test
    public void tipForCode_returnsCorrectMappings() {
        assertEquals("未检测到人脸", FaceEnrollmentController.tipForCode(0));
        assertEquals("请正对摄像头", FaceEnrollmentController.tipForCode(1));
        assertEquals("请勿遮挡面部", FaceEnrollmentController.tipForCode(2));
        assertEquals("请靠近一点", FaceEnrollmentController.tipForCode(3));
        assertEquals("请离远一点", FaceEnrollmentController.tipForCode(4));
        assertEquals("保持稳定", FaceEnrollmentController.tipForCode(5));
        assertEquals("正在检测中...", FaceEnrollmentController.tipForCode(6));
        assertEquals("请睁眼", FaceEnrollmentController.tipForCode(7));
        assertEquals("请勿低头", FaceEnrollmentController.tipForCode(8));
        assertEquals("检测中...", FaceEnrollmentController.tipForCode(9));
        assertEquals("请稍向左转", FaceEnrollmentController.tipForCode(10));
        assertEquals("请稍向右转", FaceEnrollmentController.tipForCode(11));
        assertEquals("请稍微抬头", FaceEnrollmentController.tipForCode(12));
        assertEquals("请稍微低头", FaceEnrollmentController.tipForCode(13));
        assertEquals("请保持头部竖直", FaceEnrollmentController.tipForCode(14));
    }

    @Test
    public void tipForCode_returnsNegativeCodeMappings() {
        assertEquals("请保持不动", FaceEnrollmentController.tipForCode(-10));
        assertEquals("未检测到人脸", FaceEnrollmentController.tipForCode(-7));
    }

    @Test
    public void tipForCode_returnsHighCodeMappings() {
        assertEquals("请抬头", FaceEnrollmentController.tipForCode(30));
        assertEquals("请稍向左转", FaceEnrollmentController.tipForCode(31));
        assertEquals("请稍向右转", FaceEnrollmentController.tipForCode(32));
        assertEquals("处理中...", FaceEnrollmentController.tipForCode(34));
    }

    @Test
    public void tipForCode_fallsBackToCodeFormatForUnknown() {
        assertEquals("提示码:999", FaceEnrollmentController.tipForCode(999));
        assertEquals("提示码:-1", FaceEnrollmentController.tipForCode(-1));
        assertEquals("提示码:100", FaceEnrollmentController.tipForCode(100));
    }

    @Test
    public void tipForCode_negativeCodesThatAreNotMappedAlsoFallBack() {
        assertEquals("提示码:-2", FaceEnrollmentController.tipForCode(-2));
        assertEquals("提示码:-99", FaceEnrollmentController.tipForCode(-99));
    }

    // ── 边缘情况 ────────────────────────────────────────

    @Test
    public void tipForCode_IntegerMinValueFallsBack() {
        assertEquals("提示码:-2147483648", FaceEnrollmentController.tipForCode(Integer.MIN_VALUE));
    }

    @Test
    public void tipForCode_IntegerMaxValueFallsBack() {
        assertEquals("提示码:2147483647", FaceEnrollmentController.tipForCode(Integer.MAX_VALUE));
    }
}
