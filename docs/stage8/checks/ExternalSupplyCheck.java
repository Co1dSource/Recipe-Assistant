import com.cti.ctirecipeassistant.recipe.*;
import com.cti.ctirecipeassistant.client.operation.*;
import mezz.jei.api.ingredients.*;
import java.util.*;
public class ExternalSupplyCheck {
 record Typed(String getIngredient) implements ITypedIngredient<String>{public IIngredientType<String> getType(){return ()->String.class;}}
 static TreeIngredient item(String k,long n){return new TreeIngredient(new Typed(k),k,k,n,"");}
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[]args) {
  check(new RecipeNode(item("unloaded",1),null,null,null).status().equals("外部供应"));
  var leaf=new RecipeNode(item("iron",9),null,null,null);leaf.loaded=true;
  leaf.candidates=List.of(new RecipeCandidate("machine","machine:a","A",List.of(),List.of(List.of(item("iron",1))),List.of()));
  var root=new RecipeNode(item("block",1),null,null,null);root.loaded=true;
  root.selected=new RecipeCandidate("block","minecraft:crafting","craft",List.of(List.of(item("iron",9))),List.of(List.of(item("block",1))),List.of());root.children.add(leaf);
  check(PatternPlanner.plan(root,99).issues().isEmpty());
  check(PatternPlanner.plan(root,99).steps().size()==1);
  check(leaf.status().equals("外部供应"));
  var missing=CraftPlanner.plan(root,Map.of("iron",8L));check(missing.reason().contains("iron ×1") && !missing.reason().contains("选择"));
  check(CraftPlanner.plan(root,Map.of("iron",9L)).reason()==null);
  var parent=root;for(int d=1;d<64;d++)parent=new RecipeNode(item("p"+d,1),parent,null,null);
  var boundary=new RecipeNode(item("iron",9),parent,null,null);
  root.children.clear();root.children.add(boundary);
  check(PatternPlanner.plan(root,99).issues().isEmpty());
  check(CraftPlanner.plan(root,Map.of("iron",8L)).reason().contains("iron ×1"));
  root.children.clear();
  for(int i=0;i<81;i++){var material=new RecipeNode(item("grain",1),root,null,null);material.loaded=true;material.candidates=leaf.candidates;root.children.add(material);}
  check(PatternPlanner.plan(root,99).issues().isEmpty() && PatternPlanner.plan(root,99).steps().size()==1);
  var problems=new ArrayList<PatternPlanner.Issue>();
  for(var material:root.children)problems.add(new PatternPlanner.Issue(material,PatternPlanner.ProblemType.UNSUPPORTED,"unsupported","path"));
  var merged=PatternPlanner.mergeIssues(problems);check(merged.size()==1 && merged.get(0).amount()==81);
  System.out.println("PASS unselected routes are external, no encoding issue, player stock9 works, stock8 shortage1");
 }
}
