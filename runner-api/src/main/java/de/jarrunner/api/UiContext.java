package de.jarrunner.api;

public interface UiContext {
    void setContent(UiNode root);
    void showToast(String message);
    void showDialog(String title, String message, String confirmLabel);
    void log(String message);
    String getAppDataDir();
}
