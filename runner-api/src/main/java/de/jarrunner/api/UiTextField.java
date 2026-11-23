package de.jarrunner.api;

public class UiTextField extends UiNode {
    private String value = "";
    private String hint = "";
    private TextChangeListener onChange;

    public UiTextField(String hint) {
        this.hint = hint != null ? hint : "";
    }

    public String getValue() {
        return value;
    }

    public UiTextField setValue(String value) {
        this.value = value != null ? value : "";
        return this;
    }

    public String getHint() {
        return hint;
    }

    public UiTextField setHint(String hint) {
        this.hint = hint != null ? hint : "";
        return this;
    }

    public TextChangeListener getOnChange() {
        return onChange;
    }

    public UiTextField setOnChange(TextChangeListener listener) {
        this.onChange = listener;
        return this;
    }
}
