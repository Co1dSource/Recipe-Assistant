import com.cti.ctirecipeassistant.integration.ae2.*;
import com.cti.ctirecipeassistant.client.ClientKeyEvents;
import com.cti.ctirecipeassistant.client.gui.*;
import com.cti.ctirecipeassistant.client.jei.*;
import com.cti.ctirecipeassistant.client.operation.*;
import com.cti.ctirecipeassistant.mixin.*;
import com.cti.ctirecipeassistant.recipe.*;
import mezz.jei.api.gui.*;
import mezz.jei.api.ingredients.*;
import mezz.jei.api.recipe.*;
import mezz.jei.api.runtime.*;
import mezz.jei.api.helpers.*;
import mezz.jei.gui.ingredients.IngredientLookupState;
import mezz.jei.gui.recipes.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import sun.misc.Unsafe;

public class JeiStateCheck {
 static Unsafe unsafe;
 record Typed(String getIngredient) implements ITypedIngredient<String>{public IIngredientType<String> getType(){return ()->String.class;}}
 static TreeIngredient item(String id){return new TreeIngredient(new Typed(id),id,"同名材料",1,"");}
 static RecipeNode node(String id){var n=new RecipeNode(item(id),null,null,null);n.loaded=true;return n;}
 static class PlainScreen extends Screen{PlainScreen(){super(Component.literal("test"));}}
 static class FakeMinecraft extends Minecraft{
  FakeMinecraft(){super(null);}
  @Override public void setScreen(Screen screen){this.screen=screen;}
 }
 static class FakeGui extends RecipesGui implements RecipesGuiAccessor{
  IRecipeGuiLogic testLogic;Screen parent;int opens,shows;IFocus<?> queried;
  FakeGui(){super(null,null,null,null,null,null,null,null);}
  public IRecipeGuiLogic cti$getLogic(){return testLogic;}
  public Screen cti$getParentScreen(){return parent;}
  public List<IRecipeLayoutDrawable<?>> cti$getRecipeLayouts(){return List.of();}
  public void cti$open(){parent=Minecraft.getInstance().screen;opens++;Minecraft.getInstance().setScreen(this);}
  @Override public void show(List<IFocus<?>> focuses){queried=focuses.get(0);shows++;Minecraft.getInstance().setScreen(this);}
 }
 static void set(Object target,Class<?> type,String name,Object value)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
 static void field(Object target,String name,Object value)throws Exception{set(target,target.getClass(),name,value);}
 static void check(boolean b){if(!b)throw new AssertionError();}
 @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,InvocationHandler h){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},h);}
 public static void main(String[]args)throws Exception{
  SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
  var uf=Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);unsafe=(Unsafe)uf.get(null);
  var mc=(FakeMinecraft)unsafe.allocateInstance(FakeMinecraft.class);set(null,Minecraft.class,"instance",mc);
  var original=(IngredientLookupState)unsafe.allocateInstance(IngredientLookupState.class);
  field(original,"recipeIndex",17);field(original,"recipeCategoryIndex",2);
  var other=(IngredientLookupState)unsafe.allocateInstance(IngredientLookupState.class);
  var state=new AtomicReference<>(original);
  var logic=(IRecipeGuiLogic)Proxy.newProxyInstance(IRecipeGuiLogic.class.getClassLoader(),new Class[]{IRecipeGuiLogic.class,RecipeGuiLogicAccessor.class},(p,m,a)->{
   if(m.getName().equals("cti$getState"))return state.get();
   if(m.getName().equals("cti$restoreState")){check(!(Boolean)a[1]);state.set((IngredientLookupState)a[0]);return null;}
   throw new AssertionError(m.getName());
  });
  var gui=(FakeGui)unsafe.allocateInstance(FakeGui.class);gui.testLogic=logic;gui.parent=new PlainScreen();
  var parent=gui.parent;mc.screen=gui;
  var recipeIdentity=new Object();
  var outField=RecipeInteractions.class.getDeclaredField("outputs");outField.setAccessible(true);
  var remembered=(Map<Object,String>)outField.get(null);remembered.put(recipeIdentity,"selected-secondary-output");
  set(null,RecipeInteractions.class,"outputFocus",List.of(new Typed("selected-secondary-output")));
  var context=RecipeInteractions.captureJei(gui);state.set(other);gui.parent=new PlainScreen();remembered.clear();
  RecipeInteractions.restoreJei(context);
  check(mc.screen==gui && gui.parent==parent && gui.opens==1);
  check(state.get()==original && original.getRecipeIndex()==17 && original.getRecipeCategoryIndex()==2);
  check(remembered.get(recipeIdentity).equals("selected-secondary-output"));

  var tree=(RecipeTreeScreen)unsafe.allocateInstance(RecipeTreeScreen.class);var root=node("root");
  field(tree,"root",root);field(tree,"targetRoot",root);field(tree,"roots",new ArrayDeque<>());field(tree,"panels",new ArrayDeque<>());
  field(tree,"zoom",1d);
  var first=node("first");var second=node("second");
  var issues=List.of(new PatternPlanner.Issue(first,PatternPlanner.ProblemType.UNSUPPORTED,"unsupported","a"),
    new PatternPlanner.Issue(second,PatternPlanner.ProblemType.CYCLE,"cycle","b"));
  var preview=(PatternPreviewScreen)unsafe.allocateInstance(PatternPreviewScreen.class);
  field(preview,"tree",tree);
  field(preview,"preview",new PatternEncoder.Preview(null,null,root.target,List.of(),issues,null,root,null,1));
  preview.setFocused((EditBox)unsafe.allocateInstance(EditBox.class));
  var factory=proxy(IFocusFactory.class,(p,m,a)->proxy(IFocus.class,(fp,fm,fa)->switch(fm.getName()){
    case "getTypedValue"->a[1];case "getRole"->RecipeIngredientRole.OUTPUT;default->throw new AssertionError(fm.getName());
  }));
  var helpers=proxy(IJeiHelpers.class,(p,m,a)->{if(m.getName().equals("getFocusFactory"))return factory;throw new AssertionError(m.getName());});
  TreeJeiPlugin.runtime=proxy(IJeiRuntime.class,(p,m,a)->switch(m.getName()){
   case "getRecipesGui"->gui;case "getJeiHelpers"->helpers;default->throw new AssertionError(m.getName());
  });
  var open=ClientKeyEvents.class.getDeclaredMethod("openHovered",Screen.class);open.setAccessible(true);
  for(int selected:new int[]{1,0}){
   RecipeInteractions.clear();field(preview,"problemIndex",selected);gui.shows=0;mc.screen=preview;
   check((Boolean)open.invoke(null,preview));
   check(gui.shows==1 && mc.screen==gui && gui.queried.getTypedValue()==issues.get(selected).node().target.typed());
   check(RecipeInteractions.selection().node()==issues.get(selected).node());
  }
  RecipeInteractions.clear();field(preview,"jeiContext",context);
  field(preview,"operation",new ClientRecipeOperation<>(ClientRecipeOperation.Mode.BATCH_PATTERN_ENCODING,recipeIdentity,null,null,null,null,root.target,parent));
  RecipeInteractions.arm(preview);
  check(mc.screen==gui && gui.parent==parent && remembered.get(recipeIdentity).equals(root.target.key()));
  System.out.println("PASS original JEI state/category/page/parent restore; first G with focused numeric field queries exact selected same-name node");
 }
}
