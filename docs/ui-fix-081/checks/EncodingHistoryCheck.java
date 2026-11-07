import com.cti.ctirecipeassistant.client.gui.RecipeTreeScreen;
import com.cti.ctirecipeassistant.client.jei.*;
import com.cti.ctirecipeassistant.client.operation.*;
import com.cti.ctirecipeassistant.recipe.*;
import mezz.jei.api.ingredients.*;
import mezz.jei.api.helpers.*;
import mezz.jei.api.recipe.*;
import mezz.jei.api.runtime.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.fml.loading.FMLPaths;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import sun.misc.Unsafe;

public class EncodingHistoryCheck {
    static class Client extends Minecraft {
        Client() { super(null); }
        public ServerData getCurrentServer() { return new ServerData("fixture", "history-check.invalid", false); }
        public void setScreen(Screen screen) { this.screen = screen; }
    }
    record Typed(String getIngredient) implements ITypedIngredient<String> { public IIngredientType<String> getType() { return () -> String.class; } }
    static TreeIngredient item(String key) { return new TreeIngredient(new Typed(key), key, key, 1, ""); }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    @SuppressWarnings("unchecked") static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, handler);
    }
    static Object emptyLookup(Class<?> type) { return Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type},
            (p,m,a) -> m.getName().equals("get") ? Stream.empty() : p); }
    static RecipeCandidate candidate(String key) { return new RecipeCandidate(key, "fixture:machine", "machine", List.of(), List.of(List.of(item(key))), List.of()); }
    static ClientRecipeOperation<?> operation(String key) { return new ClientRecipeOperation<>(ClientRecipeOperation.Mode.BATCH_PATTERN_ENCODING,
            new Object(), null, null, null, candidate(key), item(key), null); }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var uf=Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);var unsafe=(Unsafe)uf.get(null);
        var client=(Client)unsafe.allocateInstance(Client.class);
        var instance=Minecraft.class.getDeclaredField("instance");instance.setAccessible(true);instance.set(null,client);
        FMLPaths.loadAbsolutePaths(Path.of("outputs/ui-fix-081/fixture").toAbsolutePath());
        var factory=proxy(IFocusFactory.class,(p,m,a) -> {
            if(m.getName().equals("createFocus")) return proxy(IFocus.class,(q,n,b) -> n.getName().equals("getTypedValue") ? a[1] : RecipeIngredientRole.OUTPUT);
            if(m.getName().equals("createFocusGroup")) return proxy(IFocusGroup.class,(q,n,b) -> n.getName().equals("getAllFocuses") ? a[0] : null);
            return proxy(IFocusGroup.class,(q,n,b) -> List.of());
        });
        var helpers=proxy(IJeiHelpers.class,(p,m,a) -> factory);
        var manager=proxy(IRecipeManager.class,(p,m,a) -> m.getName().equals("createRecipeCategoryLookup") ? emptyLookup(m.getReturnType()) : Optional.empty());
        TreeJeiPlugin.runtime=proxy(IJeiRuntime.class,(p,m,a) -> m.getName().equals("getJeiHelpers") ? helpers : manager);
        RecipeInteractions.clear();
        var detached=RecipeInteractions.tree(operation("encoded"));
        check(detached.finalRoot().target.key().equals("encoded"),"encoding preview binds its exact target");
        check(!RecipeInteractions.restore(null),"encoding alone must not populate G history");
        RecipeInteractions.openItem(item("browsed"),null);
        var history=(RecipeTreeScreen)client.screen;history.finalRoot().useRecipe(candidate("browsed"));
        RecipeInteractions.tree(operation("another-encoded"));
        check(RecipeInteractions.restore(null) && client.screen==history,"G must restore explicitly browsed tree after encoding another target");
        check(RecipeInteractions.tree(operation("browsed"))==history,"encoding same target reuses explicit selections");
        System.out.println("PASS encoding preview preserves empty/explicit G history and reuses matching manual tree");
    }
}
