package fr.retrospare.blazeplayer.gallery.slideshow;

import android.app.Activity;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/** Applies system-bar and display-cutout insets without forcing Kotlin FIR to infer the listener types. */
public final class SystemBarsInsets {
    private SystemBarsInsets() {}

    public static void apply(Activity activity, int rootViewId) {
        final View root = activity.findViewById(rootViewId);
        if (root == null) return;

        final int initialLeft = root.getPaddingLeft();
        final int initialTop = root.getPaddingTop();
        final int initialRight = root.getPaddingRight();
        final int initialBottom = root.getPaddingBottom();

        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(root, new OnApplyWindowInsetsListener() {
            @Override
            public WindowInsetsCompat onApplyWindowInsets(View view, WindowInsetsCompat windowInsets) {
                Insets bars = windowInsets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                );
                view.setPadding(
                        initialLeft + bars.left,
                        initialTop + bars.top,
                        initialRight + bars.right,
                        initialBottom + bars.bottom
                );
                return windowInsets;
            }
        });
        ViewCompat.requestApplyInsets(root);
    }
}
