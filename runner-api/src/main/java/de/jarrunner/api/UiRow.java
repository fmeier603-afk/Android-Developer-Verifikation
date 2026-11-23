package de.jarrunner.api;

import java.util.ArrayList;
import java.util.List;

public class UiRow extends UiNode {
    private final List<UiNode> children = new ArrayList<>();
    private int spacingDp = 8;

    public UiRow add(UiNode child) {
        if (child != null) {
            children.add(child);
        }
        return this;
    }

    public List<UiNode> getChildren() {
        return children;
    }

    public int getSpacingDp() {
        return spacingDp;
    }

    public UiRow setSpacingDp(int spacingDp) {
        this.spacingDp = spacingDp;
        return this;
    }
}
