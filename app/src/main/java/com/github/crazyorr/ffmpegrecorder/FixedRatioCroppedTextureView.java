package com.github.crazyorr.ffmpegrecorder;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.TextureView;

import com.github.crazyorr.ffmpegrecorder.util.MiscUtils;

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
        int measuredWidth = getMeasuredWidth();
        int measuredHeight = getMeasuredHeight();
        if (measuredWidth == 0 || measuredHeight == 0) {
            return;
        }

        int width;
        int height;
        if (MiscUtils.isOrientationLandscape(getContext())) {
            height = measuredHeight;
            width = heightToWidth(measuredHeight);
            if (width > measuredWidth) {
                width = measuredWidth;
                height = widthToHeight(width);
            }
        } else {
            width = measuredWidth;
            height = widthToHeight(measuredWidth);
            if (height > measuredHeight) {
                height = measuredHeight;
                width = heightToWidth(height);
            }
        }
        setMeasuredDimension(width, height);
    }

    private int widthToHeight(int width) {
        return width * mCroppedHeightWeight / mCroppedWidthWeight;
    }

    private int heightToWidth(int height) {
        return height * mCroppedWidthWeight / mCroppedHeightWeight;
    }

    @Override
    public void layout(int l, int t, int r, int b) {
        int actualPreviewWidth;
        int actualPreviewHeight;
        int top;
        int left;
        if (MiscUtils.isOrientationLandscape(getContext())) {
            actualPreviewHeight = b - t;
            actualPreviewWidth = actualPreviewHeight * mPreviewWidth / mPreviewHeight;
            left = l + ((r - l) - actualPreviewWidth) / 2;
            top = t;
        } else {
            actualPreviewWidth = r - l;
            actualPreviewHeight = actualPreviewWidth * mPreviewHeight / mPreviewWidth;
            top = t + ((b - t) - actualPreviewHeight) / 2;
            left = l;
        }
        super.layout(left, top, left + actualPreviewWidth, top + actualPreviewHeight);
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
