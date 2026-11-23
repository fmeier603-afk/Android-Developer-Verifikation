package de.jarrunner.api;

public class UiText extends UiNode {
    private String text = "";
    private String style = "body";
    private String colorHex = "";

    public UiText(String text) {
        this.text = text != null ? text : "";
    }

    public String getText() {
        return text;
    }

    public UiText setText(String text) {
        this.text = text != null ? text : "";
        return this;
    }

    public String getStyle() {
        return style;
    }

    public UiText setStyle(String style) {
        this.style = style != null ? style : "body";
        return this;
    }

    public String getColorHex() {
        return colorHex;
    }

    public UiText setColorHex(String colorHex) {
        this.colorHex = colorHex != null ? colorHex : "";
        return this;
    }
}
