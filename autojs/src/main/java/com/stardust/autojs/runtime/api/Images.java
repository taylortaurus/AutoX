package com.stardust.autojs.runtime.api;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.Image;
import android.os.Build;
import android.os.Handler;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import com.nickandjerry.dynamiclayoutinflator.lib.ImageLoader;
import com.nickandjerry.dynamiclayoutinflator.lib.util.Drawables;
import com.stardust.autojs.annotation.ScriptVariable;
import com.stardust.autojs.core.image.ColorFinder;
import com.stardust.autojs.core.image.ImageWrapper;
import com.stardust.autojs.core.image.ScreenCaptureRequester;
import com.stardust.autojs.core.image.ScreenCapturer;
import com.stardust.autojs.core.image.TemplateMatching;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import com.stardust.concurrent.VolatileDispose;
import com.stardust.pio.UncheckedIOException;
import com.stardust.util.ScreenMetrics;

import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Created by Stardust on 2017/5/20.
 */
@RequiresApi(api = Build.VERSION_CODES.KITKAT)
public class Images {

    private ScriptRuntime mScriptRuntime;
    private ScreenCaptureRequester mScreenCaptureRequester;
    private ScreenCapturer mScreenCapturer;
    private Context mContext;
    private Display mDisplay;
    private Image mPreCapture;
    private ImageWrapper mPreCaptureImage;

    @ScriptVariable
    public final ColorFinder colorFinder;

    public Images(Context context, ScriptRuntime scriptRuntime, ScreenCaptureRequester screenCaptureRequester) {
        mScriptRuntime = scriptRuntime;
        mScreenCaptureRequester = screenCaptureRequester;
        mContext = context;
        mDisplay = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        colorFinder = new ColorFinder();
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public boolean requestScreenCapture(final int width, final int height) {
        mScriptRuntime.requiresApi(21);
        final VolatileDispose<Boolean> requestResult = new VolatileDispose<>();
        mScreenCaptureRequester.setOnActivityResultCallback((result, data) -> {
            if (result == Activity.RESULT_OK) {
                mScreenCapturer = new ScreenCapturer(mContext, data, width, height, ScreenMetrics.getDeviceScreenDensity(),
                        new Handler(mScriptRuntime.loopers.getServantLooper()));
                requestResult.setAndNotify(true);
            } else {
                requestResult.setAndNotify(false);
            }
        });
        mScreenCaptureRequester.request();
        return requestResult.blockedGetOrThrow(ScriptInterruptedException.class);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public boolean requestScreenCapture(boolean landscape) {
        if (!landscape)
            return requestScreenCapture(ScreenMetrics.getDeviceScreenWidth(), ScreenMetrics.getDeviceScreenHeight());
        else
            return requestScreenCapture(ScreenMetrics.getDeviceScreenHeight(), ScreenMetrics.getDeviceScreenWidth());
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public boolean requestScreenCapture() {
        if (mDisplay.getRotation() == Surface.ROTATION_0 || mDisplay.getRotation() == Surface.ROTATION_180)
            return requestScreenCapture(ScreenMetrics.getDeviceScreenWidth(), ScreenMetrics.getDeviceScreenHeight());
        else
            return requestScreenCapture(ScreenMetrics.getDeviceScreenHeight(), ScreenMetrics.getDeviceScreenWidth());
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public ImageWrapper captureScreen() {
        mScriptRuntime.requiresApi(21);
        if (mScreenCapturer == null) {
            throw new SecurityException("No screen capture permission");
        }
        Image capture = mScreenCapturer.capture();
        if (capture == mPreCapture) {
            return mPreCaptureImage;
        }
        mPreCapture = capture;
        mPreCaptureImage = ImageWrapper.ofImage(capture);
        return mPreCaptureImage;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public boolean captureScreen(String path) {
        ImageWrapper image = captureScreen();
        if (image != null) {
            saveImage(image, path);
            return true;
        }
        return false;
    }

    public void saveImage(ImageWrapper image, String path) {
        image.saveTo(path);
    }


    public static int pixel(Image image, int x, int y) {
        int originX = x;
        int originY = y;
        ScreenMetrics metrics = new ScreenMetrics(image.getWidth(), image.getHeight());
        x = metrics.rescaleX(x);
        y = metrics.rescaleY(y);
        Image.Plane plane = image.getPlanes()[0];
        int offset = y * plane.getRowStride() + x * plane.getPixelStride();
        int c = plane.getBuffer().getInt(offset);
        Log.d("Images", String.format(Locale.getDefault(), "(%d, %d)→(%d, %d)", originX, originY, x, y));
        return (c & 0xff000000) + ((c & 0xff) << 16) + (c & 0x00ff00) + ((c & 0xff0000) >> 16);
    }

    public static int pixel(ImageWrapper image, int x, int y) {
        x = ScreenMetrics.rescaleX(x, image.getWidth());
        y = ScreenMetrics.rescaleY(y, image.getHeight());
        return image.pixel(x, y);
    }


    public ImageWrapper read(String path) {
        Bitmap bitmap = BitmapFactory.decodeFile(path);
        return ImageWrapper.ofBitmap(bitmap);
    }

    public ImageWrapper load(String src) {
        try {
            URL url = new URL(src);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.connect();
            InputStream input = connection.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            return ImageWrapper.ofBitmap(bitmap);
        } catch (IOException e) {
            return null;
        }
    }

    public static void saveBitmap(Bitmap bitmap, String path) {
        try {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, new FileOutputStream(path));
        } catch (FileNotFoundException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Bitmap scaleBitmap(Bitmap origin, int newWidth, int newHeight) {
        if (origin == null) {
            return null;
        }
        int height = origin.getHeight();
        int width = origin.getWidth();
        float scaleWidth = ((float) newWidth) / width;
        float scaleHeight = ((float) newHeight) / height;
        Matrix matrix = new Matrix();
        matrix.postScale(scaleWidth, scaleHeight);
        return Bitmap.createBitmap(origin, 0, 0, width, height, matrix, false);
    }

    public void releaseScreenCapturer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mScreenCapturer != null) {
            mScreenCapturer.release();
        }
    }

    public Point findImage(ImageWrapper image, ImageWrapper template) {
        return findImage(image, template, 0.9f, null);
    }

    public Point findImage(ImageWrapper image, ImageWrapper template, float threshold) {
        return findImage(image, template, threshold, null);
    }

    public Point findImage(ImageWrapper image, ImageWrapper template, float threshold, Rect rect) {
        return findImage(image, template, 0.7f, threshold, rect, TemplateMatching.MAX_LEVEL_AUTO);
    }

    public Point findImage(ImageWrapper image, ImageWrapper template, float weakThreshold, float threshold, Rect rect, int maxLevel) {
        Mat src = image.getMat();
        if (rect != null) {
            src = new Mat(src, rect);
        }
        org.opencv.core.Point point = TemplateMatching.fastTemplateMatching(src, template.getMat(), TemplateMatching.MATCHING_METHOD_DEFAULT,
                weakThreshold, threshold, maxLevel);
        if (point != null && rect != null) {
            point.x += rect.x;
            point.y += rect.y;
        }
        return point;
    }


}
