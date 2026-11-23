package de.jarrunner.api;

public interface CanvasRenderer {
    void onDraw(UiCanvas canvas, int width, int height, long frameTick);
}
