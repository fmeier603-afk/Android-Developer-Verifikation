package de.jarrunner.api.internal;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;

public class SandboxClassLoader extends URLClassLoader {
    private static final List<String> BLOCKED_PREFIXES = Arrays.asList(
        "java.lang.Runtime",
        "java.lang.ProcessBuilder",
        "java.lang.ProcessImpl",
        "java.lang.reflect.",
        "java.lang.invoke.MethodHandles$Lookup",
        "java.nio.file.",
        "sun.misc.Unsafe",
        "jdk.internal."
    );

    public SandboxClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        for (String blocked : BLOCKED_PREFIXES) {
            if (name.startsWith(blocked)) {
                throw new SecurityException("Sandbox-Verletzung: Zugriff auf '" + name + "' blockiert.");
            }
        }
        return super.loadClass(name, resolve);
    }
}
