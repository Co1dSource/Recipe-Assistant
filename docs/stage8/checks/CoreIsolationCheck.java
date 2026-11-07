import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;

/** Link core signatures with all AE2/ExtendedAE classes forbidden. Not a Forge launch. */
public class CoreIsolationCheck {
    static class Isolated extends ClassLoader {
        int optionalRequests;
        Isolated() { super(CoreIsolationCheck.class.getClassLoader()); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("appeng.") || name.startsWith("com.glodblock.") || name.contains(".integration.ae2.")) {
                optionalRequests++; throw new ClassNotFoundException("Optional class forbidden: " + name);
            }
            if (!name.startsWith("com.cti.ctirecipeassistant.")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                var type = findLoadedClass(name);
                if (type == null) {
                    try { byte[] code = Files.readAllBytes(Path.of("build/classes/java/main", name.replace('.', '/') + ".class"));
                        type = defineClass(name, code, 0, code.length);
                    } catch (java.io.IOException e) { throw new ClassNotFoundException(name, e); }
                }
                if (resolve) resolveClass(type); return type;
            }
        }
    }
    public static void main(String[] args) throws Exception {
        var loader = new Isolated(); int count = 0;
        try (var files = Files.walk(Path.of("build/classes/java/main/com/cti/ctirecipeassistant"))) {
            for (var path : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = Path.of("build/classes/java/main").relativize(path).toString().replace('\\', '.').replace('/', '.').replace(".class", "");
                if (name.contains(".integration.ae2.") || name.contains("PatternAccess") || name.endsWith("FtbHoveredIngredient") || name.endsWith("TinkersCraftingInventory")) continue;
                Class<?> type = Class.forName(name, false, loader);
                type.getDeclaredFields(); type.getDeclaredMethods(); type.getDeclaredConstructors(); count++;
            }
        }
        Class<?> bridge = Class.forName("com.cti.ctirecipeassistant.integration.OptionalIntegrations", true, loader);
        if ((Boolean)bridge.getMethod("busy").invoke(null)) throw new AssertionError("AE2 active without installation");
        if (loader.optionalRequests != 0) throw new AssertionError("Optional class resolution attempted " + loader.optionalRequests);
        System.out.println("PASS core signature linkage: " + count + " classes; AE2/ExtendedAE resolutions=0");
    }
}
