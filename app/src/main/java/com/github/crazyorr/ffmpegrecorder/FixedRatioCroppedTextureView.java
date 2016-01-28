package com.github.crazyorr.ffmpegrecorder;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.TextureView;

/**
 * Created by wanglei02 on 2016/1/6.
 */
public class FixedRatioCroppedTextureView extends TextureView {
    private int mPreviewWidth;
    private int mPreviewHeight;
    private int mCroppedWidthWeight;
    private int mCroppedHeightWeight;

    public FixedRatioCroppedTextureView(Context context) {
        super(context);
    }

    public FixedRatioCroppedTextureView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FixedRatioCroppedTextureView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public FixedRatioCroppedTextureView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int width = this.getMeasuredWidth();
        setMeasuredDimension(width, width * mCroppedHeightWeight / mCroppedWidthWeight);
    }

    @Override
    public void layout(int l, int t, int r, int b) {
        int actualPreviewWidth = r - l;
        int actualPreviewHeight = actualPreviewWidth * mPreviewHeight / mPreviewWidth;
        int top = t + ((b - t) - actualPreviewHeight) / 2;
        super.layout(l, top, r, top + actualPreviewHeight);
    }

    public void setPreviewSize(int previewWidth, int previewHeight) {
        mPreviewWidth = previewWidth;
        mPreviewHeight = previewHeight;
    }

    public void setCroppedSizeWeight(int widthWeight, int heightWeight) {
        this.mCroppedWidthWeight = widthWeight;
        this.mCroppedHeightWeight = heightWeight;
    }
}
