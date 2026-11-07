package appeng.crafting.pattern;
import com.cti.ctirecipeassistant.integration.ae2.PatternEncoder;
import appeng.api.stacks.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.CraftingRecipe;
import java.lang.reflect.*;
import java.util.*;
import sun.misc.Unsafe;

public class SubstitutionCheck {
 static void check(boolean value){if(!value)throw new AssertionError();}
 static void set(Class<?> type,Object instance,String name,Object value)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);f.set(instance,value);}
 public static void main(String[]args)throws Exception{
  SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
  var uf=Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);var unsafe=(Unsafe)uf.get(null);
  var recipe=(CraftingRecipe)Proxy.newProxyInstance(CraftingRecipe.class.getClassLoader(),new Class[]{CraftingRecipe.class},
    (p,m,a)->m.getName().equals("getId")?new ResourceLocation("test","recipe"):null);
  var inputs=new ItemStack[9];Arrays.fill(inputs,ItemStack.EMPTY);inputs[0]=new ItemStack(Items.STONE);
  var matches=PatternEncoder.class.getDeclaredMethod("matchesSubstitution",AECraftingPattern.class);matches.setAccessible(true);
  for(boolean items:new boolean[]{false,true})for(boolean fluids:new boolean[]{false,true}){
   set(PatternEncoder.class,null,"batchSubstitute",items);set(PatternEncoder.class,null,"batchSubstituteFluids",fluids);
   for(int batch=0;batch<3;batch++){
    var data=new CompoundTag();
    CraftingPatternEncoding.encodeCraftingPattern(data,recipe,inputs,new ItemStack(Items.PAPER),items,fluids);
    check(CraftingPatternEncoding.canSubstitute(data)==items && CraftingPatternEncoding.canSubstituteFluids(data)==fluids);
    var pattern=(AECraftingPattern)unsafe.allocateInstance(AECraftingPattern.class);
    set(AECraftingPattern.class,pattern,"canSubstitute",CraftingPatternEncoding.canSubstitute(data));
    set(AECraftingPattern.class,pattern,"canSubstituteFluids",CraftingPatternEncoding.canSubstituteFluids(data));
    check((Boolean)matches.invoke(null,pattern));
    set(AECraftingPattern.class,pattern,"canSubstitute",!items);check(!(Boolean)matches.invoke(null,pattern));
    var processing=new CompoundTag();
    ProcessingPatternEncoding.encodeProcessingPattern(processing,new GenericStack[]{new GenericStack(AEItemKey.of(Items.STONE),1)},new GenericStack[]{new GenericStack(AEItemKey.of(Items.PAPER),1)});
    check(processing.getAllKeys().size()==2); // Native processing format has inputs/outputs, no substitution flags.
   }
  }
  System.out.println("PASS all4 substitution combinations x3 native encodings and product validation; processing native schema unchanged");
 }
}
