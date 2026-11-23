package de.jarrunner.api;

public class UiButton extends UiNode {
    private String label = "Button";
    private String variant = "primary";
    private ClickListener onClick;

    public UiButton(String label) {
        this.label = label != null ? label : "Button";
    }

    public String getLabel() {
        return label;
    }

    public UiButton setLabel(String label) {
        this.label = label != null ? label : "";
        return this;
    }

    public String getVariant() {
        return variant;
    }

    public UiButton setVariant(String variant) {
        this.variant = variant != null ? variant : "primary";
        return this;
    }

    public ClickListener getOnClick() {
        return onClick;
    }

    public UiButton setOnClick(ClickListener listener) {
        this.onClick = listener;
        return this;
    }
}
