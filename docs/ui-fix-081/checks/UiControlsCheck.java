import com.cti.ctirecipeassistant.integration.ae2.*;
import appeng.client.gui.me.patternaccess.PatternAccessTermScreen;
import appeng.client.gui.widgets.Scrollbar;
import appeng.client.Point;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Vector4f;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import java.lang.reflect.*;
import java.util.*;
import sun.misc.Unsafe;

public class UiControlsCheck {
    static class Box extends EditBox {
        String captured;
        Box() { super(null,0,0,0,0,Component.empty()); }
        public void setValue(String text) { captured=text; }
    }
    static float z(PoseStack pose) { var p=new Vector4f(0,0,0,1); p.transform(pose.last().pose()); return p.z(); }
    static class TooltipScreen extends Screen {
        float depth; List<Component> lines;
        TooltipScreen() { super(Component.empty()); }
        public void renderComponentTooltip(PoseStack pose,List<Component> lines,int x,int y) { depth=z(pose);this.lines=lines; }
    }
    static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
    static void set(Class<?> c,Object instance,String name,Object value)throws Exception {var f=c.getDeclaredField(name);f.setAccessible(true);f.set(instance,value);}
    public static void main(String[] args)throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        var uf=Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);var unsafe=(Unsafe)uf.get(null);
        var preview=(PatternPreviewScreen)unsafe.allocateInstance(PatternPreviewScreen.class);
        var box=(Box)unsafe.allocateInstance(Box.class);set(PatternPreviewScreen.class,preview,"depthBox",box);
        var adjust=PatternPreviewScreen.class.getDeclaredMethod("adjustDepth",int.class);adjust.setAccessible(true);
        int[][] cases={{0,-1,0},{0,1,1},{3,-1,2},{3,1,4},{Integer.MAX_VALUE,1,Integer.MAX_VALUE}};
        for(var c:cases) {set(PatternPreviewScreen.class,preview,"depth",c[0]);adjust.invoke(preview,c[1]);check(box.captured.equals(Integer.toString(c[2])),"depth increment/decrement boundary");}
        var terminal=(PatternAccessTermScreen<?>)unsafe.allocateInstance(PatternAccessTermScreen.class);
        set(AbstractContainerScreen.class,terminal,"leftPos",40);set(AbstractContainerScreen.class,terminal,"topPos",30);
        var bx=DistributionPreview.class.getDeclaredMethod("buttonX",PatternAccessTermScreen.class);bx.setAccessible(true);
        var by=DistributionPreview.class.getDeclaredMethod("buttonY",PatternAccessTermScreen.class);by.setAccessible(true);
        var size=DistributionPreview.class.getDeclaredField("BUTTON_SIZE");size.setAccessible(true);
        var scrollbar=new Scrollbar();scrollbar.setPosition(new Point(175,18));var bounds=scrollbar.getBounds();
        check((Integer)bx.invoke(null,terminal)-40==bounds.getX() && size.getInt(null)==bounds.getWidth(),"button aligned exactly with native scrollbar column");
        check(bounds.getY()-((Integer)by.invoke(null,terminal)-30+size.getInt(null))>=5,"button separated from scrollbar");
        var screen=(TooltipScreen)unsafe.allocateInstance(TooltipScreen.class);var pose=new PoseStack();pose.translate(0,0,700);
        var tooltip=DistributionPreview.class.getDeclaredMethod("renderTooltip",Screen.class,PoseStack.class,List.class,int.class,int.class);tooltip.setAccessible(true);
        tooltip.invoke(null,screen,pose,List.of(Component.literal("自动分发")),100,100);
        check(screen.lines.get(0).getString().equals("自动分发"),"distribution tooltip retained");
        check(screen.depth+400>700 && screen.depth+400<1000 && z(pose)==700,"tooltip above overlay, inside clipping, caller pose restored");
        System.out.println("PASS depth +/- saturation, native scrollbar alignment/gap, tooltip text/layer and pose restore");
    }
}
