package de.jarrunner.api;

import java.util.ArrayList;
import java.util.List;

public class UiCanvas extends UiNode {
    private int heightDp = 240;
    private String backgroundHex = "#0B1120";
    private CanvasRenderer renderer;
    private final List<String> commands = new ArrayList<>();

    public int getHeightDp() {
        return heightDp;
    }

    public UiCanvas setHeightDp(int heightDp) {
        this.heightDp = heightDp;
        return this;
    }

    public String getBackgroundHex() {
        return backgroundHex;
    }

    public UiCanvas setBackgroundHex(String backgroundHex) {
        this.backgroundHex = backgroundHex != null ? backgroundHex : "#0B1120";
        return this;
    }

    public CanvasRenderer getRenderer() {
        return renderer;
    }

    public UiCanvas setRenderer(CanvasRenderer renderer) {
        this.renderer = renderer;
        return this;
    }

    public List<String> getCommands() {
        return commands;
    }

    public void clear() {
        commands.clear();
    }

    public void drawCircle(float x, float y, float radius, String colorHex) {
        commands.add("CIRCLE;" + x + ";" + y + ";" + radius + ";" + colorHex);
    }

    public void drawRect(float x, float y, float width, float height, String colorHex) {
        commands.add("RECT;" + x + ";" + y + ";" + width + ";" + height + ";" + colorHex);
    }

    public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth, String colorHex) {
        commands.add("LINE;" + x1 + ";" + y1 + ";" + x2 + ";" + y2 + ";" + strokeWidth + ";" + colorHex);
    }

    public void drawText(String text, float x, float y, float size, String colorHex) {
        String safe = text != null ? text.replace(";", ",") : "";
        commands.add("TEXT;" + x + ";" + y + ";" + size + ";" + colorHex + ";" + safe);
    }
}
