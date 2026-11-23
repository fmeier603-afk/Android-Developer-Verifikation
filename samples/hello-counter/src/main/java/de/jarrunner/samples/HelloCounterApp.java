package de.jarrunner.samples;

import de.jarrunner.api.*;
import java.util.ArrayList;
import java.util.List;

public class HelloCounterApp implements RunnerApp {
    private UiContext ctx;
    private int count = 0;
    private String userName = "Java-Entwickler";
    private final List<String> history = new ArrayList<>();

    @Override
    public void onStart(UiContext context) {
        this.ctx = context;
        history.add("App gestartet in Sandbox: " + context.getAppDataDir());
        context.log("HelloCounterApp initialisiert.");
        render();
    }

    private void render() {
        UiColumn root = new UiColumn().setSpacingDp(14);

        root.add(new UiText("Hallo, " + userName + "!")
            .setStyle("headline")
            .setColorHex("#F59E0B"));

        root.add(new UiText("Standard-JAR-Bytecode läuft isoliert über die native UI-Bridge.")
            .setStyle("body"));

        root.add(new UiText("Aktueller Zählerstand: " + count)
            .setStyle("mono")
            .setColorHex("#22D3EE"));

        UiRow actions = new UiRow().setSpacingDp(10);
        actions.add(new UiButton("Klick +1 (" + count + ")")
            .setVariant("primary")
            .setOnClick(new ClickListener() {
                @Override
                public void onClick() {
                    count++;
                    history.add(0, "Klick #" + count + " von " + userName);
                    if (history.size() > 6) {
                        history.remove(history.size() - 1);
                    }
                    ctx.log("Button geklickt -> count=" + count);
                    if (count % 5 == 0) {
                        ctx.showToast("Meilenstein erreicht: " + count + " Klicks!");
                    }
                    render();
                }
            }));

        actions.add(new UiButton("Reset")
            .setVariant("outlined")
            .setOnClick(new ClickListener() {
                @Override
                public void onClick() {
                    count = 0;
                    history.add(0, "Zähler zurückgesetzt");
                    ctx.showDialog("Zähler zurückgesetzt", "Der Klick-Zähler steht wieder auf 0.", "Verstanden");
                    render();
                }
            }));

        root.add(actions);

        root.add(new UiTextField("Dein Name")
            .setValue(userName)
            .setOnChange(new TextChangeListener() {
                @Override
                public void onTextChanged(String newText) {
                    userName = newText.isEmpty() ? "Gast" : newText;
                    render();
                }
            }));

        root.add(new UiText("Ereignis-Protokoll:")
            .setStyle("caption"));

        UiList list = new UiList().setItems(history);
        list.setOnItemClick(new ItemClickListener() {
            @Override
            public void onItemClick(int index, String item) {
                ctx.showToast("Eintrag #" + (index + 1) + ": " + item);
            }
        });
        root.add(list);

        ctx.setContent(root);
    }
}
