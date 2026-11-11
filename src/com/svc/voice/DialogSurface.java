package com.svc.voice;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import java.util.function.Consumer;

/** Lifecycle-bound dialog glass; preserves application dismiss callbacks. */
public final class DialogSurface {
    private DialogSurface() {}
    public static Builder builder(Context context) { return new Builder(context); }
    public static final class Builder extends AlertDialog.Builder {
        public Builder(Context context) { super(context); }
        @Override public AlertDialog create() {
            AlertDialog dialog = super.create();
            dialog.setOnShowListener(ignored -> attach(dialog));
            return dialog;
        }
    }
    private static void attach(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        WindowManager manager = dialog.getContext().getSystemService(WindowManager.class);
        View decor = window.getDecorView();
        Consumer<Boolean> update = enabled -> {
            float density = dialog.getContext().getResources().getDisplayMetrics().density;
            boolean dark = (dialog.getContext().getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            GradientDrawable surface = new GradientDrawable();
            surface.setColor(dark ? (enabled ? 0xB8282828 : 0xFF282828)
                                  : (enabled ? 0xCCEAEAEA : 0xFFF4F4F4));
            surface.setCornerRadius(24 * density);
            surface.setStroke(Math.max(1, (int)density), dark ? 0x38FFFFFF : 0x40808080);
            window.setBackgroundDrawable(surface);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = enabled ? .22f : .42f;
            if (Build.VERSION.SDK_INT >= 31) {
                if (enabled) attrs.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                else attrs.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                attrs.setBlurBehindRadius(enabled ? (int)(24 * density) : 0);
                window.setBackgroundBlurRadius(enabled ? (int)(32 * density) : 0);
            }
            window.setAttributes(attrs);
            decor.setClipToOutline(true);
        };
        if (Build.VERSION.SDK_INT >= 31 && manager != null) {
            View.OnAttachStateChangeListener cleanup = new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {}
                @Override public void onViewDetachedFromWindow(View view) {
                    manager.removeCrossWindowBlurEnabledListener(update);
                    view.removeOnAttachStateChangeListener(this);
                }
            };
            decor.addOnAttachStateChangeListener(cleanup);
            manager.addCrossWindowBlurEnabledListener(dialog.getContext().getMainExecutor(), update);
            update.accept(manager.isCrossWindowBlurEnabled());
        } else update.accept(false);
    }
}
