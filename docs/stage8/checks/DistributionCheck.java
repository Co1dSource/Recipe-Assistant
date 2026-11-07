package com.cti.ctirecipeassistant.integration.ae2;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.client.gui.me.patternaccess.PatternContainerRecord;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.*;
import net.minecraft.network.chat.Component;
import appeng.api.stacks.AEItemKey;
import java.util.*;
import com.cti.ctirecipeassistant.recipe.RecipePreferences;
import com.google.gson.JsonObject;
import sun.misc.Unsafe;
public class DistributionCheck {
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[]args) throws Exception {
  SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
  var enabled=Ae2Integration.class.getDeclaredField("extendedProviders");enabled.setAccessible(true);
  enabled.setBoolean(null,false);check(Ae2Integration.supportsProvider(9) && !Ae2Integration.supportsProvider(36));
  enabled.setBoolean(null,true);check(Ae2Integration.supportsProvider(36) && !Ae2Integration.supportsProvider(27));
  var g=new PatternContainerGroup(AEItemKey.of(Items.IRON_INGOT),Component.literal("机器A"),List.of());
  var small=new PatternContainerRecord(1,9,1,g);var large=new PatternContainerRecord(2,36,2,g);
  var a=PatternDistribution.provider(small);var b=PatternDistribution.provider(large);
  check(a.free()==9 && b.free()==36);check(a.type().contains("普通") && b.type().contains("扩展"));
  check(a.key().equals(b.key()));check(!a.key().contains("serverId"));
  var one=new ItemStack(Items.PAPER,1);var many=new ItemStack(Items.PAPER,9);
  check(PatternRecordStore.content(one).equals(PatternRecordStore.content(many)));
  small.getInventory().setItemDirect(0,one);
  check(PatternDistribution.contains(a,one));check(PatternDistribution.provider(small).free()==8);
  var record=new JsonObject();record.addProperty("type","Processing");record.addProperty("machine","机器A");
  check(PatternDistribution.matches(record,a));
  record.addProperty("machine","机器B");check(!PatternDistribution.matches(record,a));
  var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var unsafe=(Unsafe)f.get(null);
  var prefs=(RecipePreferences)unsafe.allocateInstance(RecipePreferences.class);
  var dataField=RecipePreferences.class.getDeclaredField("data");dataField.setAccessible(true);dataField.set(prefs,new JsonObject());
  var writableField=RecipePreferences.class.getDeclaredField("writable");writableField.setAccessible(true);writableField.set(prefs,false);
  record.addProperty("machine","机器A");record.addProperty("categoryUid","test:machine");
  var row=new PatternDistribution.Row(one,List.of(record),3);
  PatternDistribution.plan(List.of(row),List.of(a,b),prefs,Map.of());check(row.provider==null);
  prefs.put("provider-group|"+row.route(),b.key());
  PatternDistribution.plan(List.of(row),List.of(a,b),prefs,Map.of(row.route(),2L));check(row.provider.id()==2 && row.upload==1 && row.duplicate==2);
  PatternDistribution.plan(List.of(row),List.of(a),prefs,Map.of(row.route(),2L));check(row.provider==null);
  var ambiguous=new PatternDistribution.Row(one,List.of(record,record.deepCopy()),1);
  PatternDistribution.plan(List.of(ambiguous),List.of(b),prefs,Map.of());check(ambiguous.provider==null && ambiguous.category==-1);
  ambiguous.category=0;
  PatternDistribution.plan(List.of(ambiguous),List.of(a),prefs,Map.of());
  check(ambiguous.upload==0 && ambiguous.duplicate==0 && ambiguous.reason.contains("类别"));
  ambiguous.confirmedDuplicate=ambiguous.route()+"|"+a.id();
  PatternDistribution.plan(List.of(ambiguous),List.of(a),prefs,Map.of());check(ambiguous.duplicate==1);
  PatternDistribution.plan(List.of(row),List.of(a),prefs,Map.of());check(row.duplicate==3 && row.upload==0);
  for(int i=0;i<large.getInventory().size();i++)large.getInventory().setItemDirect(i,new ItemStack(Items.STONE));
  PatternDistribution.plan(List.of(row),List.of(PatternDistribution.provider(large)),prefs,Map.of());check(row.upload==0 && row.reason.contains("剩余 0"));
  System.out.println("PASS 9/36 slots, group key excludes session id, count-independent content, duplicate, machine mismatch; ambiguous category/providers, stale session binding, duplicate counts, full slots");
 }
}
