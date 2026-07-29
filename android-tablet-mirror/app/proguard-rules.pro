# The framework instantiates these by name from the manifest.
-keep class com.siliconprime.tabletmirror.host.MirrorAccessibilityService { *; }
-keep class com.siliconprime.tabletmirror.host.ScreenCaptureService { *; }

# Views inflated from XML are referenced only by their layout name.
-keep class com.siliconprime.tabletmirror.viewer.AspectRatioSurfaceView {
    public <init>(android.content.Context, android.util.AttributeSet);
}
