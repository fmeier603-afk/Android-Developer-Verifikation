package de.jarrunner.samples;

import de.jarrunner.api.*;

public class OrbitCanvasApp implements RunnerApp {
    private UiContext ctx;
    private double speed = 1.0;
    private boolean paused = false;
    private int orbitCount = 3;

    @Override
    public void onStart(UiContext context) {
        this.ctx = context;
        context.log("OrbitCanvasApp gestartet (2D UiCanvas Bridge).");
        render();
    }

    private void render() {
        UiColumn root = new UiColumn().setSpacingDp(12);

        root.add(new UiText("2D Canvas Bridge")
            .setStyle("headline")
            .setColorHex("#22D3EE"));

        root.add(new UiText("Geschwindigkeit: " + speed + "x | Bahnen: " + orbitCount + (paused ? " (Pausiert)" : ""))
            .setStyle("mono"));

        UiCanvas canvas = new UiCanvas()
            .setHeightDp(260)
            .setBackgroundHex("#090F1D")
            .setRenderer(new CanvasRenderer() {
                @Override
                public void onDraw(UiCanvas c, int width, int height, long frameTick) {
                    float cx = width / 2.0f;
                    float cy = height / 2.0f;
                    double t = paused ? 0.0 : (frameTick * 0.04 * speed);

                    // Core glow
                    c.drawCircle(cx, cy, 24.0f, "#F59E0B");
                    c.drawCircle(cx, cy, 12.0f, "#FEF3C7");

                    int i = 1;
                    while (i <= orbitCount) {
                        float radius = 38.0f * i;
                        double angle = t * (4.0 - i * 0.7) + (i * 1.1);
                        float px = cx + (float) (Math.cos(angle) * radius);
                        float py = cy + (float) (Math.sin(angle) * radius);

                        c.drawLine(cx, cy, px, py, 2.0f, "#1E293B");
                        String color = i == 1 ? "#22D3EE" : (i == 2 ? "#10B981" : "#A78BFA");
                        c.drawCircle(px, py, 10.0f, color);
                        i++;
                    }
                    c.drawText("FRAME " + frameTick, 16.0f, 28.0f, 13.0f, "#94A3B8");
                }
            });
        root.add(canvas);

        UiRow controls = new UiRow().setSpacingDp(10);
        controls.add(new UiButton(paused ? "Fortsetzen" : "Pause")
            .setVariant("primary")
            .setOnClick(new ClickListener() {
                @Override
                public void onClick() {
                    paused = !paused;
                    ctx.log("Canvas " + (paused ? "pausiert" : "aktiv"));
                    render();
                }
            }));

        controls.add(new UiButton("Tempo +")
            .setVariant("tonal")
            .setOnClick(new ClickListener() {
                @Override
                public void onClick() {
                    if (speed < 4.0) {
                        speed = speed + 0.5;
                    }
                    render();
                }
            }));

        controls.add(new UiButton("Bahn +")
            .setVariant("outlined")
            .setOnClick(new ClickListener() {
                @Override
                public void onClick() {
                    orbitCount = orbitCount >= 4 ? 1 : orbitCount + 1;
                    ctx.showToast("Umlaufbahnen: " + orbitCount);
                    render();
                }
            }));

        root.add(controls);
        ctx.setContent(root);
    }
}
