package com.foretree.apng;

import android.graphics.Bitmap;
import android.os.SystemClock;

import java.util.concurrent.TimeUnit;

/**
 * Created by xiejing on 16/3/28.
 *
 * 负责调度每帧的解码和绘制
 */
public class ApngRenderTask implements Runnable {
    private ApngDrawable apngDrawable;
    private ApngFrameDecode apngDecode;
    public ApngRenderTask(ApngDrawable apngDrawable, ApngFrameDecode apngDecode) {
        this.apngDrawable = apngDrawable;
        this.apngDecode = apngDecode;
    }
    @Override
    public void run() {
        int nextFrame = apngDrawable.currentFrame+1;
        if (nextFrame >= apngDecode.frameCount) {
            if (apngDrawable.needRepeat()) {
                apngDrawable.currentFrame = -1;
                nextFrame = 0;
            } else {
                return;
            }
        }
        long startTime = SystemClock.uptimeMillis();
        // 创建
        Bitmap bitmap = apngDecode.createFrameBitmap(nextFrame);

        if (apngDrawable.frameBp != null && apngDrawable.frameBp != bitmap) {
            apngDrawable.bitmapCache.reuseBitmap(apngDrawable.frameBp);
        }
        apngDrawable.frameBp = bitmap;
        apngDrawable.currentFrame++;
        long takeTime = SystemClock.uptimeMillis()-startTime;
        int delay = apngDecode.getFrameDelay(nextFrame);
        // 把解码耗的时间减掉
        delay -= takeTime;

        // 定时下一次任务
        apngDrawable.excutor.schedule(this, delay, TimeUnit.MILLISECONDS);

        // 通知ui刷新
        if (apngDrawable.isVisible() && apngDrawable.isRunning() && !apngDrawable.invalidationHandler.hasMessages(0)) {
            apngDrawable.invalidationHandler.sendEmptyMessageAtTime(0, 0);
        }
    }
}
