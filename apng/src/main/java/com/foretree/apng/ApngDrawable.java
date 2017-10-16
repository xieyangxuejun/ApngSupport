package com.foretree.apng;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

import ar.com.hjg.pngj.PngReaderApng;


/**
 * Reference: http://www.vogella.com/code/com.vogella.android.drawables.animation/src/com/vogella/android/drawables/animation/ColorAnimationDrawable.html
 * <p>
 * apng解码优化版本
 * apng文件格式
 * http://www.zhangxinxu.com/wordpress/2014/09/apng-history-character-maker-editor/
 * https://developer.mozilla.org/en-US/docs/Mozilla/Tech/APNG
 */
public class ApngDrawable extends Drawable implements Animatable {
    public static final String TAG = "ApngDrawable";
    private static final int INFINITE_LOOP = 0;     //循环播放
    protected final Uri sourceUri;
    private Paint paint;
    protected String workingPath;
    private final ImageView.ScaleType scaleType; // 支持FIT_XY(默认), CENTER_CROP, CENTER_INSIDE
    private ApngPlayListener playListener = null;
    private boolean isRunning = false;

    private RectF canvasRect;
    int baseWidth;
    int baseHeight;
    protected int currentFrame;
    private int currentLoop;

    ScheduledThreadPoolExecutor excutor = null;//new ScheduledThreadPoolExecutor(1, new ThreadPoolExecutor.DiscardPolicy());
    ApngFrameDecode frameDecode;
    ApngBitmapCache bitmapCache;
    ApngInvalidationHandler invalidationHandler;
    Bitmap frameBp;


    /**
     * @param bitmap
     * @param uri
     * @param scaleType // 支持FIT_XY(默认), CENTER_CROP, CENTER_INSIDE
     */
    public ApngDrawable(Context context, Bitmap bitmap, Uri uri, ImageView.ScaleType scaleType) {
        super();

        // 解码器
        frameDecode = new ApngFrameDecode(this);
        // 缓存器
        bitmapCache = new ApngBitmapCache();

        this.scaleType = scaleType;
        currentFrame = -1;
        currentLoop = 0;

        paint = new Paint();
        paint.setAntiAlias(true);

        workingPath = ApngImageUtils.getImageCachePath(context);
        sourceUri = uri;

        if (bitmap != null && bitmap.isMutable()) {
            bitmapCache.cacheBitmap(0, bitmap);
        }
        // 图片的宽和高不通过bitmap来获取, 因为bitmap可能由于内存原因而修改sampleSize, 从而影响了图片的宽高
        //baseWidth = bitmap.getWidth();
        //baseHeight = bitmap.getHeight();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(sourceUri.getPath(), options);
        baseWidth = options.outWidth;
        baseHeight = options.outHeight;

        invalidationHandler = new ApngInvalidationHandler(this);
    }

    public void setPlayListener(ApngPlayListener listener) {
        playListener = listener;
    }

    /**
     * Specify number of repeating. Note that this will override the value described in APNG file
     *
     * @param numPlays Number of repeating
     */
    public void setNumPlays(int numPlays) {
        frameDecode.playCount = numPlays;
    }

    public void decodePrepare() {
        if (!frameDecode.isPrepared) {
            frameDecode.prepare();
        }
    }

    // 是否需要再次播放
    boolean needRepeat() {
        currentLoop++;
        if (currentLoop < frameDecode.playCount || frameDecode.playCount == INFINITE_LOOP) {
            // 到ui线程通知动画开始执行
            invalidationHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (playListener != null) {
                        playListener.onAnimationRepeat(ApngDrawable.this);
                    }
                }
            });
            return true;
        } else {
            // 结束播放
            invalidationHandler.post(new Runnable() {
                @Override
                public void run() {
                    stop();
                }
            });
            return false;
        }
    }

    @Override
    public void start() {
        if (!isRunning()) {
            isRunning = true;
            currentFrame = 0;

            if (excutor != null) {
                excutor.shutdownNow();
            }
            excutor = new ScheduledThreadPoolExecutor(1, new ThreadPoolExecutor.DiscardPolicy());

            if (!frameDecode.isPrepared) {
                //if (enableDebugLog) Log.d(TAG, "Prepare");
                // 异步进行文件初始化准备
                excutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        //执行动画的时候必须条用prepare来初始化
                        frameDecode.prepare();
                    }
                });
            }

            // 开始播放动画
            excutor.execute(new Runnable() {
                @Override
                public void run() {
                    frameDecode.startRenderFrame();
                    // 到ui线程通知动画开始执行
                    invalidationHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (frameDecode.isPrepared) {
                                if (playListener != null) {
                                    playListener.onAnimationStart(ApngDrawable.this);
                                }
                                ApngDrawable.this.invalidateSelf();
                            } else {
                                stop();
                            }
                        }
                    });
                }
            });
        }
    }

    @Override
    public void stop() {
        if (isRunning()) {
            currentLoop = 0;
            //unscheduleSelf(this);
            isRunning = false;
            if (excutor != null) {
                excutor.shutdownNow();
                excutor = null;
            }
            if (playListener != null) {
                playListener.onAnimationEnd(this);
            }
            // 清空缓存
            bitmapCache.clear();
        }
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    @Override
    public void draw(Canvas canvas) {
        if (currentFrame <= 0) {
            frameBp = bitmapCache.getCacheBitmap(0);
        }
        if (frameBp != null) {
            drawBitmap(canvas, frameBp);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private void drawBitmap(Canvas canvas, Bitmap frameBitmap) {
        if (canvasRect == null) {
            canvasRect = calculateCanvasRect(canvas);
        }
        canvas.drawBitmap(frameBitmap, null, canvasRect, paint);
    }

    // 根据scaleType的设置计算画布对应的位置
    private RectF calculateCanvasRect(Canvas canvas) {

        RectF calculateResult = null;

        int canvasWidth = canvas.getWidth();
        int canvasHeight = canvas.getHeight();

        float scalingByWidth = ((float) canvasWidth) / baseWidth;
        float scalingByHeight = ((float) canvasHeight) / baseHeight;

        float x, y, w, h;
        switch (scaleType) {
            case CENTER_CROP:
                if (scalingByWidth > scalingByHeight) {
                    w = canvasWidth;
                    h = baseHeight * scalingByWidth;
                    x = 0;
                    y = 0 - (h - canvasHeight) / 2;
                } else {
                    w = baseWidth * scalingByHeight;
                    h = canvasHeight;
                    x = 0 - (w - canvasWidth) / 2;
                    y = 0;
                }
                break;
            case CENTER_INSIDE:
                if (scalingByWidth > scalingByHeight) {
                    w = baseWidth * scalingByHeight;
                    h = canvasHeight;
                    x = (canvasWidth - w) / 2;
                    y = 0;
                } else {
                    w = canvasWidth;
                    h = baseHeight * scalingByWidth;
                    x = 0;
                    y = (canvasHeight - h) / 2;
                }
                break;
            case FIT_XY:
            default:
                x = 0;
                y = 0;
                w = canvasWidth;
                h = canvasHeight;
                break;
        }
        calculateResult = new RectF(x, y, x + w, y + h);
        return calculateResult;
    }

    // 获取apng图片的路径
    String getImagePathFromUri() {
        if (sourceUri == null) return null;

        String imagePath = null;

        try {
            String filename = sourceUri.getLastPathSegment();

            File file = new File(workingPath, filename);

            if (!file.exists()) {
                ApngImageUtils.copyFile(sourceUri.getPath(), file.getPath(), false);
            }

            imagePath = file.getPath();

        } catch (Exception e) {
            e.printStackTrace();
        }

        return imagePath;
    }

    /**
     * Check a file whether it is APNG
     *
     * @param file Target file
     * @return True if a file is APNG
     */
    public static boolean isApng(File file) {

        boolean isApng = false;
        try {
            PngReaderApng reader = new PngReaderApng(file);
            reader.end();
            int apngNumFrames = reader.getApngNumFrames();
            isApng = apngNumFrames > 1;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return isApng;
    }

    /**
     * @return 获取apng的解码器
     */
    public ApngFrameDecode getFrameDecode() {
        return frameDecode;
    }
}
