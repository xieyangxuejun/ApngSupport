package com.foretree.apng;

import android.graphics.drawable.Drawable;
import android.widget.ImageView;


/**
 * apng加载监听
 * Created by xieyang on 17/10/13.
 */
public interface ILoadingListener<D extends Drawable> {

    void onLoadingComplete(String uri, ImageView imageView, D drawable);

    void onLoadFailed(String uri, ImageView imageView);
}
