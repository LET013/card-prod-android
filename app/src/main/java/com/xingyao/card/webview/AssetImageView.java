package com.xingyao.card.webview;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatImageView;

import java.io.IOException;
import java.io.InputStream;

/** 初始化页复用 H5 启动页的品牌图标。 */
public final class AssetImageView extends AppCompatImageView {
    private static final String LOGO_ASSET = "static/brand/logo-white.png";

    public AssetImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        try (InputStream input = context.getAssets().open(LOGO_ASSET)) {
            setImageBitmap(BitmapFactory.decodeStream(input));
        } catch (IOException ignored) {
            // WebView 启动页会继续展示同一资源；原生初始化层保持纯色背景。
        }
    }
}
