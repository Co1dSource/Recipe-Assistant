import com.cti.ctirecipeassistant.client.ClientKeyEvents;
import com.cti.ctirecipeassistant.client.jei.*;
import mezz.jei.api.runtime.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.client.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraftforge.fml.ModList;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Stream;
import sun.misc.Unsafe;

public class HoverCheck {
 static class StopAtTarget extends RuntimeException{}
 static class ContainerScreen extends AbstractContainerScreen<AbstractContainerMenu>{
  Slot hovered;
  ContainerScreen(){super(null,null,Component.literal("test"));}
  @Override public Slot getSlotUnderMouse(){return hovered;}
  @Override protected void renderBg(PoseStack pose,float partial,int x,int y){}
 }
 static void set(Class<?> c,Object o,String n,Object v)throws Exception{var f=c.getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
 static void check(boolean b){if(!b)throw new AssertionError();}
 static Object proxy(Class<?> c,InvocationHandler h){return Proxy.newProxyInstance(c.getClassLoader(),new Class[]{c},h);}
 public static void main(String[]args)throws Exception{
  SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
  var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var unsafe=(Unsafe)f.get(null);
  var mc=(JeiStateCheck.FakeMinecraft)unsafe.allocateInstance(JeiStateCheck.FakeMinecraft.class);
  set(Minecraft.class,null,"instance",mc);
  var window=(Window)unsafe.allocateInstance(Window.class);
  for(String n:List.of("width","height","guiScaledWidth","guiScaledHeight"))set(Window.class,window,n,320);
  set(Minecraft.class,mc,"window",window);
  set(Minecraft.class,mc,"mouseHandler",unsafe.allocateInstance(MouseHandler.class));
  var player=(LocalPlayer)unsafe.allocateInstance(LocalPlayer.class);
  var inventory=new Inventory(player);set(Player.class,player,"inventory",inventory);mc.player=player;
  var mods=(ModList)unsafe.allocateInstance(ModList.class);set(ModList.class,mods,"indexedMods",new HashMap<>());
  set(ModList.class,null,"INSTANCE",mods);
  var captured=new ItemStack[]{ItemStack.EMPTY};
  var ingredients=proxy(IIngredientManager.class,(p,m,a)->{
   if(m.getName().equals("createTypedIngredient")){captured[0]=(ItemStack)a[1];throw new StopAtTarget();}
   throw new AssertionError(m.getName());
  });
  TreeJeiPlugin.runtime=(IJeiRuntime)proxy(IJeiRuntime.class,(p,m,a)->switch(m.getName()){
   case "getIngredientManager"->ingredients;
   case "getIngredientListOverlay","getBookmarkOverlay","getRecipesGui"->proxy(m.getReturnType(),(q,k,b)->switch(k.getName()){
    case "hasKeyboardFocus"->false;case "getIngredientUnderMouse"->Optional.empty();default->throw new AssertionError(k.getName());
   });
   case "getScreenHelper"->proxy(m.getReturnType(),(q,k,b)->Stream.empty());
   default->throw new AssertionError(m.getName());
  });
  var screen=(ContainerScreen)unsafe.allocateInstance(ContainerScreen.class);
  var open=ClientKeyEvents.class.getDeclaredMethod("openHovered",Screen.class);open.setAccessible(true);
  inventory.setItem(9,new ItemStack(Items.IRON_INGOT));
  var slots=List.of(new Slot(inventory,9,0,0),new Slot(new SimpleContainer(new ItemStack(Items.GOLD_INGOT)),0,0,0));
  for(var slot:slots){
   RecipeInteractions.clear();screen.hovered=slot;mc.screen=screen;captured[0]=ItemStack.EMPTY;
   check(player.getMainHandItem().isEmpty());
   try{open.invoke(null,screen);}catch(InvocationTargetException e){if(!(e.getCause() instanceof StopAtTarget))throw e;}
   check(ItemStack.matches(captured[0],slot.getItem()));
  }
  System.out.println("PASS empty-hand G reaches hovered inventory item and hovered container item outside JEI");
 }
}
