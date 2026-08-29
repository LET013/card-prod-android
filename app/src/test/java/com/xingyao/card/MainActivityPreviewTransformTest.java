package com.xingyao.card;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MainActivityPreviewTransformTest {

    @Test
    public void centerCropScale_preservesFourByThreeCameraAspectInPortraitCard() {
        assertEquals(1.875f,
                MainActivity.calculateCenterCropScale(720, 900, 640, 480),
                0.0001f);
    }

    @Test
    public void centerCropScale_keepsMatchingAspectUniformlyScaled() {
        assertEquals(1.5f,
                MainActivity.calculateCenterCropScale(1920, 1080, 1280, 720),
                0.0001f);
    }
}
