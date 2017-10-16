package com.foretree.apng;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.util.Pair;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import ar.com.hjg.pngj.PngReaderApng;
import ar.com.hjg.pngj.chunks.PngChunk;
import ar.com.hjg.pngj.chunks.PngChunkACTL;
import ar.com.hjg.pngj.chunks.PngChunkFCTL;

/**
 * Created by xiejing on 16/3/28.
 * <p>
 * Apng解码类
 * <p>
 * 1. prepare(); 首先需要调用prepare()对apng文件进行拆解, 包括拆成每帧的小文件, 以及读取块信息等;
 * 2. createFrameBitmap(frameIndex); 然后调用createFrameBitmap进行每帧数据的解码
 */
public class ApngFrameDecode {

    static final float DELAY_FACTOR = 1000F;

    protected boolean isPrepared = false;

    private File baseFile;
    protected int frameCount;
    protected int playCount;
    private ArrayList<PngChunkFCTL> fctlArrayList = new ArrayList<>();
    private Map<Integer, Pair<Integer, Integer>> frameWHMap = new HashMap<Integer, Pair<Integer, Integer>>();// 存储着每帧的宽高, 因为解析后面帧的时候可能需要前面帧的宽高

    ApngDrawable apngDrawable;
    ApngRenderTask renderTask;

    public ApngFrameDecode() {
    }

    protected ApngFrameDecode(ApngDrawable apngDrawable) {
        this.apngDrawable = apngDrawable;
        renderTask = new ApngRenderTask(apngDrawable, this);
    }

    // 播放apng之前需要调用这个函数进行初始化准备
    protected void prepare() {
        String imagePath = apngDrawable.getImagePathFromUri();
        if (imagePath == null) return;
        baseFile = new File(imagePath);
        if (!baseFile.exists()) return;
        ApngExtractFrames.process(baseFile);
        readApngInformation(baseFile);
        isPrepared = true;
        //scheduleSelf(this, SystemClock.uptimeMillis() + delay);
    }

    /**
     * 初始化解码器
     * @param filePath 文件地址
     */
    public void prepare(String filePath) {
        if (filePath == null) return;
        baseFile = new File(filePath);
        if (!baseFile.exists()) return;
        ApngExtractFrames.process(baseFile);
        readApngInformation(baseFile);
        isPrepared = true;
    }

    protected void startRenderFrame() {
        if (apngDrawable.currentFrame < 0) {
            apngDrawable.currentFrame = 0;
        } else if (apngDrawable.currentFrame >= fctlArrayList.size() - 1) {
            apngDrawable.currentFrame = 0;
        }

        // 生成第一张图片
        createFrameBitmap(0);
        int delay = getFrameDelay(0);

        apngDrawable.excutor.schedule(renderTask, delay, TimeUnit.MILLISECONDS);
    }

    // 获取每帧的延迟
    public int getFrameDelay(int frameIndex) {
        PngChunkFCTL pngChunk = fctlArrayList.get(frameIndex);
        int delayNum = pngChunk.getDelayNum();
        int delayDen = pngChunk.getDelayDen();
        int delay = Math.round(delayNum * ApngFrameDecode.DELAY_FACTOR / delayDen);
        return delay;
    }

    // 解码每帧
    public Bitmap createFrameBitmap(final int frameIndex) {
        if (frameIndex == 0) {
            // 生成第一张图片
            String imagePath = apngDrawable.getImagePathFromUri();
            Bitmap bitmap = apngDrawable.bitmapCache.getCacheBitmap(0);
            if (bitmap == null) {
                bitmap = ApngImageUtils.decodeFileToDrawable(ApngImageUtils.Scheme.FILE.wrap(imagePath),
                        apngDrawable.bitmapCache.getReuseBitmap(apngDrawable.baseWidth, apngDrawable.baseHeight));
                apngDrawable.bitmapCache.cacheBitmap(0, bitmap);
            }
            return bitmap;
        }

        Bitmap currentBitmap = null;
        String path = new File(apngDrawable.workingPath, ApngExtractFrames.getFileName(baseFile, frameIndex)).getPath();
        try {
            Bitmap clipBitmap = apngDrawable.bitmapCache.getReuseBitmap(apngDrawable.baseWidth, apngDrawable.baseHeight);
            currentBitmap = ApngImageUtils.decodeFileToDrawable(ApngImageUtils.Scheme.FILE.wrap(path), clipBitmap);
            if (clipBitmap != currentBitmap) {
                apngDrawable.bitmapCache.reuseBitmap(clipBitmap);
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        // 把该帧图片的宽高存储起来, 后面可能需要
        if (currentBitmap != null) {
            frameWHMap.put(frameIndex, new Pair<>(currentBitmap.getWidth(), currentBitmap.getHeight()));
        }

        // 2. 先合成出上一帧图片
        Bitmap previousBitmap = handleDisposeOperation(frameIndex);

        // 3. 使用上一帧图片和当前帧图片合成出当前需要展示的图片
        Bitmap complexBitmap;
        PngChunkFCTL chunk = fctlArrayList.get(frameIndex);
        byte blendOp = chunk.getBlendOp();
        int offsetX = chunk.getxOff();
        int offsetY = chunk.getyOff();
        complexBitmap = handleBlendingOperation(offsetX, offsetY, blendOp, currentBitmap, previousBitmap);

        // 同时保存到缓存
        apngDrawable.bitmapCache.cacheBitmap(frameIndex, complexBitmap);

        apngDrawable.bitmapCache.reuseBitmap(currentBitmap);
        apngDrawable.bitmapCache.reuseBitmap(previousBitmap);

        return complexBitmap;
    }

    // 初始化的时候读取apng文件块信息
    private void readApngInformation(File apngFile) {
        PngReaderApng reader = new PngReaderApng(apngFile);
        reader.end();

        List<PngChunk> pngChunks = reader.getChunksList().getChunks();
        PngChunk chunk;

        // 计算最大需要的缓存大小
        int maxCacheSize = 1;

        for (int i = 0; i < pngChunks.size(); i++) {
            chunk = pngChunks.get(i);

            if (chunk instanceof PngChunkACTL) {
                frameCount = ((PngChunkACTL) chunk).getNumFrames();

                if (playCount > 0) {
                    //if (enableDebugLog) Log.d(TAG, "numPlays: " + numPlays + " (user defined)");
                } else {
                    playCount = ((PngChunkACTL) chunk).getNumPlays();
                    //if (enableDebugLog) Log.d(TAG, "numPlays: " + numPlays + " (media info)");
                }
            } else if (chunk instanceof PngChunkFCTL) {
                fctlArrayList.add((PngChunkFCTL) chunk);

                // 计算最大需要的缓存大小
                PngChunkFCTL calculateChunk = (PngChunkFCTL) chunk;
                int calculateIndex = fctlArrayList.size() - 1;
                int calculateMaxCacheSize = 1;
                while (calculateChunk.getDisposeOp() == PngChunkFCTL.APNG_DISPOSE_OP_PREVIOUS && calculateIndex > 0) {
                    calculateIndex--;
                    calculateMaxCacheSize++;
                    calculateChunk = fctlArrayList.get(calculateIndex);
                }
                maxCacheSize = Math.max(maxCacheSize, calculateMaxCacheSize);
            }
        }

        if (apngDrawable != null) apngDrawable.bitmapCache.setMaxCacheSize(maxCacheSize);
    }

    /**
     * Process Blending operation, and handle a final draw for this frame
     */
    private Bitmap handleBlendingOperation(
            int offsetX, int offsetY, byte blendOp,
            Bitmap frameBitmap, Bitmap baseBitmap) {

        Bitmap redrawnBitmap = null;
        if (baseBitmap != null && baseBitmap.getWidth() == apngDrawable.baseWidth
                && baseBitmap.getHeight() == apngDrawable.baseHeight
                && !apngDrawable.bitmapCache.cacheContain(baseBitmap)) {
            redrawnBitmap = baseBitmap;
        } else {
            redrawnBitmap = apngDrawable.bitmapCache.getReuseBitmap(apngDrawable.baseWidth, apngDrawable.baseHeight);
        }

        if (redrawnBitmap == null) return baseBitmap;

        Canvas canvas = new Canvas(redrawnBitmap);

        if (baseBitmap != null) {
            if (redrawnBitmap != baseBitmap) {
                canvas.drawBitmap(baseBitmap, 0, 0, null);
            }

            if (blendOp == PngChunkFCTL.APNG_BLEND_OP_SOURCE) {
                canvas.clipRect(offsetX, offsetY, offsetX + frameBitmap.getWidth(), offsetY + frameBitmap.getHeight());
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                canvas.clipRect(0, 0, apngDrawable.baseWidth, apngDrawable.baseHeight);
            }
        }

        canvas.drawBitmap(frameBitmap, offsetX, offsetY, null);

        return redrawnBitmap;
    }

    private Bitmap handleDisposeOperation(int frameIndex) {
        PngChunkFCTL previousChunk = frameIndex > 0 ? fctlArrayList.get(frameIndex - 1) : null;
        if (previousChunk == null) return null;

        Bitmap bitmap = null;

        byte disposeOp = previousChunk.getDisposeOp();
        int offsetX = previousChunk.getxOff();
        int offsetY = previousChunk.getyOff();

        Canvas tempCanvas;
        Bitmap tempBitmap;
        switch (disposeOp) {
            case PngChunkFCTL.APNG_DISPOSE_OP_NONE:
                //Log.v("tempTest", "APNG_DISPOSE_OP_NONE for " + frameIndex);
                // Do Not Dispose：把当前帧增量绘制到画布上，不清空画布
                // Get bitmap from the previous frame
                bitmap = frameIndex > 0 ? apngDrawable.bitmapCache.getCacheBitmap(frameIndex - 1) : null;
                break;

            case PngChunkFCTL.APNG_DISPOSE_OP_BACKGROUND:
                //Log.v("tempTest", "APNG_DISPOSE_OP_BACKGROUND for " + frameIndex);
                // Restore to Background：绘制当前帧之前，先把画布清空为默认背景色
                // Get bitmap from the previous frame but the drawing region is needed to be cleared
                bitmap = frameIndex > 0 ? apngDrawable.bitmapCache.getCacheBitmap(frameIndex - 1) : null;
                if (bitmap == null) break;

                //if (enableDebugLog) Log.d(TAG, "Create a new bitmap");
                if (bitmap != null && frameWHMap.containsKey(frameIndex - 1)) {
                    tempBitmap = apngDrawable.bitmapCache.getReuseBitmap(apngDrawable.baseWidth, apngDrawable.baseHeight);
                    if (tempBitmap == null) break;
                    tempCanvas = new Canvas(tempBitmap);
                    tempCanvas.drawBitmap(bitmap, 0, 0, null);

                    tempCanvas.clipRect(offsetX, offsetY, offsetX + frameWHMap.get(frameIndex - 1).first, offsetY + frameWHMap.get(frameIndex - 1).second);
                    tempCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                    tempCanvas.clipRect(0, 0, apngDrawable.baseWidth, apngDrawable.baseHeight);

                    bitmap = tempBitmap;
                }
                break;

            case PngChunkFCTL.APNG_DISPOSE_OP_PREVIOUS:
                //Log.v("tempTest", "APNG_DISPOSE_OP_PREVIOUS for " + frameIndex);
                // Restore to Previous：绘制下一帧前，把先把画布恢复为当前帧的前一帧
                if (frameIndex > 1) {
                    PngChunkFCTL tempPngChunk;
                    for (int i = frameIndex - 2; i >= 0; i--) {
                        tempPngChunk = fctlArrayList.get(i);
                        int tempDisposeOp = tempPngChunk.getDisposeOp();
                        int tempOffsetX = tempPngChunk.getxOff();
                        int tempOffsetY = tempPngChunk.getyOff();
                        if (tempDisposeOp != PngChunkFCTL.APNG_DISPOSE_OP_PREVIOUS) {
                            if (tempDisposeOp == PngChunkFCTL.APNG_DISPOSE_OP_NONE) {
                                bitmap = apngDrawable.bitmapCache.getCacheBitmap(i);
                            } else if (tempDisposeOp == PngChunkFCTL.APNG_DISPOSE_OP_BACKGROUND) {
                                //if (enableDebugLog) Log.d(TAG, "Create a new bitmap");
                                bitmap = apngDrawable.bitmapCache.getCacheBitmap(i);
                                if (bitmap != null && frameWHMap.containsKey(i)) {
                                    tempBitmap = apngDrawable.bitmapCache.getReuseBitmap(apngDrawable.baseWidth, apngDrawable.baseHeight);
                                    if (tempBitmap == null) break;
                                    tempCanvas = new Canvas(tempBitmap);
                                    tempCanvas.drawBitmap(bitmap, 0, 0, null);

                                    tempCanvas.clipRect(tempOffsetX, tempOffsetY, tempOffsetX + frameWHMap.get(i).first, tempOffsetY + frameWHMap.get(i).second);
                                    tempCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                                    tempCanvas.clipRect(0, 0, apngDrawable.baseWidth, apngDrawable.baseHeight);

                                    bitmap = tempBitmap;
                                }
                            }
                            break;
                        }
                    }
                }
                break;
        }
        return bitmap;
    }

    public int getFrameCount() {
        return frameCount;
    }
}
