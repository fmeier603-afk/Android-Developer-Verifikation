package de.jarrunner.api;

import java.util.ArrayList;
import java.util.List;

public class UiList extends UiNode {
    private final List<String> items = new ArrayList<>();
    private ItemClickListener onItemClick;

    public List<String> getItems() {
        return items;
    }

    public UiList setItems(List<String> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        return this;
    }

    public UiList addItem(String item) {
        if (item != null) {
            items.add(item);
        }
        return this;
    }

    public ItemClickListener getOnItemClick() {
        return onItemClick;
    }

    public UiList setOnItemClick(ItemClickListener listener) {
        this.onItemClick = listener;
        return this;
    }
}
