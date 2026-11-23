package de.jarrunner.api.internal;

import de.jarrunner.api.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChildProcessHost {
    private static final Map<String, ClickListener> clickListeners = new HashMap<>();
    private static final Map<String, TextChangeListener> textListeners = new HashMap<>();
    private static final Map<String, ItemClickListener> itemListeners = new HashMap<>();
    private static final Map<String, UiCanvas> canvasNodes = new HashMap<>();
    private static UiNode currentRoot;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: ChildProcessHost <jarPath> <mainClass>");
            System.exit(1);
        }
        File jarFile = new File(args[0]);
        String mainClassName = args[1];
        String sandboxDir = args.length >= 3 ? args[2] : System.getProperty("user.dir");

        URL[] urls = new URL[] { jarFile.toURI().toURL() };
        try (SandboxClassLoader loader = new SandboxClassLoader(urls, ChildProcessHost.class.getClassLoader())) {
            Class<?> appClass = loader.loadClass(mainClassName);
            Object instance = appClass.getDeclaredConstructor().newInstance();
            if (!(instance instanceof RunnerApp)) {
                throw new IllegalStateException("Main class must implement de.jarrunner.api.RunnerApp");
            }

            UiContext ctx = new UiContext() {
                @Override
                public void setContent(UiNode root) {
                    currentRoot = root;
                    emitUiTree(root);
                }

                @Override
                public void showToast(String message) {
                    emitLine("TOAST|" + escape(message));
                }

                @Override
                public void showDialog(String title, String message, String confirmLabel) {
                    emitLine("DIALOG|" + escape(title) + "|" + escape(message) + "|" + escape(confirmLabel));
                }

                @Override
                public void log(String message) {
                    emitLine("LOG|" + escape(message));
                }

                @Override
                public String getAppDataDir() {
                    return sandboxDir;
                }
            };

            RunnerApp app = (RunnerApp) instance;
            app.onStart(ctx);

            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals("EXIT")) break;
                if (line.startsWith("CLICK|")) {
                    String nodeId = line.substring(6);
                    ClickListener listener = clickListeners.get(nodeId);
                    if (listener != null) listener.onClick();
                } else if (line.startsWith("TEXT|")) {
                    int sep = line.indexOf('|', 5);
                    if (sep > 5) {
                        String nodeId = line.substring(5, sep);
                        String val = unescape(line.substring(sep + 1));
                        TextChangeListener listener = textListeners.get(nodeId);
                        if (listener != null) listener.onTextChanged(val);
                    }
                } else if (line.startsWith("ITEM|")) {
                    String[] parts = line.split("\\|", 4);
                    if (parts.length == 4) {
                        ItemClickListener listener = itemListeners.get(parts[1]);
                        if (listener != null) {
                            listener.onItemClick(Integer.parseInt(parts[2]), unescape(parts[3]));
                        }
                    }
                } else if (line.startsWith("FRAME|")) {
                    String[] parts = line.split("\\|");
                    if (parts.length >= 5) {
                        UiCanvas canvas = canvasNodes.get(parts[1]);
                        if (canvas != null && canvas.getRenderer() != null) {
                            canvas.clear();
                            canvas.getRenderer().onDraw(
                                canvas,
                                Integer.parseInt(parts[2]),
                                Integer.parseInt(parts[3]),
                                Long.parseLong(parts[4])
                            );
                            emitLine("CANVAS|" + canvas.getId() + "|" + String.join("||", canvas.getCommands()));
                        }
                    }
                }
            }
        }
    }

    private static void emitUiTree(UiNode root) {
        clickListeners.clear();
        textListeners.clear();
        itemListeners.clear();
        canvasNodes.clear();
        StringBuilder sb = new StringBuilder("UI|");
        serializeNode(root, sb);
        emitLine(sb.toString());
    }

    private static void serializeNode(UiNode node, StringBuilder sb) {
        if (node == null) return;
        if (node instanceof UiColumn col) {
            sb.append("{\"type\":\"column\",\"id\":\"").append(node.getId()).append("\",\"spacing\":").append(col.getSpacingDp()).append(",\"children\":[");
            List<UiNode> ch = col.getChildren();
            for (int i = 0; i < ch.size(); i++) {
                if (i > 0) sb.append(",");
                serializeNode(ch.get(i), sb);
            }
            sb.append("]}");
        } else if (node instanceof UiRow row) {
            sb.append("{\"type\":\"row\",\"id\":\"").append(node.getId()).append("\",\"spacing\":").append(row.getSpacingDp()).append(",\"children\":[");
            List<UiNode> ch = row.getChildren();
            for (int i = 0; i < ch.size(); i++) {
                if (i > 0) sb.append(",");
                serializeNode(ch.get(i), sb);
            }
            sb.append("]}");
        } else if (node instanceof UiText txt) {
            sb.append("{\"type\":\"text\",\"id\":\"").append(node.getId()).append("\",\"text\":\"").append(jsonEscape(txt.getText())).append("\",\"style\":\"").append(txt.getStyle()).append("\",\"color\":\"").append(txt.getColorHex()).append("\"}");
        } else if (node instanceof UiButton btn) {
            if (btn.getOnClick() != null) clickListeners.put(btn.getId(), btn.getOnClick());
            sb.append("{\"type\":\"button\",\"id\":\"").append(node.getId()).append("\",\"label\":\"").append(jsonEscape(btn.getLabel())).append("\",\"variant\":\"").append(btn.getVariant()).append("\"}");
        } else if (node instanceof UiTextField tf) {
            if (tf.getOnChange() != null) textListeners.put(tf.getId(), tf.getOnChange());
            sb.append("{\"type\":\"textfield\",\"id\":\"").append(node.getId()).append("\",\"value\":\"").append(jsonEscape(tf.getValue())).append("\",\"hint\":\"").append(jsonEscape(tf.getHint())).append("\"}");
        } else if (node instanceof UiList list) {
            if (list.getOnItemClick() != null) itemListeners.put(list.getId(), list.getOnItemClick());
            sb.append("{\"type\":\"list\",\"id\":\"").append(node.getId()).append("\",\"items\":[");
            List<String> items = list.getItems();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(jsonEscape(items.get(i))).append("\"");
            }
            sb.append("]}");
        } else if (node instanceof UiCanvas canvas) {
            canvasNodes.put(canvas.getId(), canvas);
            sb.append("{\"type\":\"canvas\",\"id\":\"").append(node.getId()).append("\",\"heightDp\":").append(canvas.getHeightDp()).append(",\"bg\":\"").append(canvas.getBackgroundHex()).append("\"}");
        }
    }

    private static void emitLine(String line) {
        System.out.println(line);
        System.out.flush();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\n", "\\n").replace("|", "\\p");
    }

    private static String unescape(String s) {
        return s == null ? "" : s.replace("\\p", "|").replace("\\n", "\n").replace("\\\\", "\\");
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
