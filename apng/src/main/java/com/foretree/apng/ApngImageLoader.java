package com.foretree.apng;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 图片加载器
 */
public class ApngImageLoader {
    private static ApngImageLoader mInstance;
    private Context mContext;
    private Handler mHandler;
    private ScheduledThreadPoolExecutor mExcutor;

    private ApngImageLoader() {
        mHandler = new Handler(Looper.getMainLooper());
        mExcutor = new ScheduledThreadPoolExecutor(1, new ThreadPoolExecutor.DiscardPolicy());
    }

    public static ApngImageLoader getInstance(Context context) {
        if (mInstance == null) {
            synchronized (ApngImageLoader.class) {
                if (mInstance == null) {
                    mInstance = new ApngImageLoader();
                    mInstance.mContext = context;
                }
            }
        }
        return mInstance;
    }

    public Context getAppContext() {
        return mContext;
    }

    public void loadImage(final String uri, final ImageView imageView, final ILoadingListener listener) {
        mInstance.mExcutor.execute(new Runnable() {
            @Override
            public void run() {
                ApngImageUtils.Scheme urlType = ApngImageUtils.Scheme.ofUri(uri);
                Bitmap decodeBitmap = null;
                switch (urlType) {
                    case FILE:
                        decodeBitmap = ApngImageUtils.decodeFileToDrawable(uri, null);
                        break;
                    case ASSETS:
                        String filePath = ApngImageUtils.Scheme.ASSETS.crop(uri);
                        try {
                            InputStream inputStream = mInstance.mContext.getAssets().open(filePath);
                            decodeBitmap = BitmapFactory.decodeStream(inputStream);
                        } catch (IOException | OutOfMemoryError e) {
                            e.printStackTrace();
                        }
                        break;
                }

                // 将结果通知ui业务层
                final Drawable finalDrawable = ApngImageUtils.bitmapToDrawable(mInstance.mContext, uri, imageView, decodeBitmap);
                mInstance.mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (finalDrawable != null) {
                            Drawable oldDrawable = imageView.getDrawable();
                            if (oldDrawable != finalDrawable && oldDrawable != null && oldDrawable instanceof ApngDrawable) {
                                ((ApngDrawable) oldDrawable).stop();
                            }
                            imageView.setImageDrawable(finalDrawable);
                            if (listener != null)
                                listener.onLoadingComplete(uri, imageView, finalDrawable);
                            if (finalDrawable instanceof ApngDrawable) {
                                ApngDrawable apngDrawable = (ApngDrawable) finalDrawable;
                                apngDrawable.setNumPlays(0);
                                apngDrawable.start();
                            }
                        } else {
                            if (listener != null)
                                listener.onLoadFailed(uri, imageView);
                        }
                    }
                });
            }
        });
    }
}
