package de.jarrunner.api;

public abstract class UiNode {
    private static int nextId = 1;
    private final String id;
    private int paddingDp = 0;

    protected UiNode() {
        this.id = "node_" + (nextId++);
    }

    public String getId() {
        return id;
    }

    public int getPaddingDp() {
        return paddingDp;
    }

    public UiNode setPaddingDp(int paddingDp) {
        this.paddingDp = paddingDp;
        return this;
    }
}
