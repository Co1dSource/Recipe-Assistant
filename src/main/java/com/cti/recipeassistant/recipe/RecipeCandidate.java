package com.cti.recipeassistant.recipe;

import java.util.List;

public record RecipeCandidate(String id, String category, String machine,
                              List<List<TreeIngredient>> inputs, List<List<TreeIngredient>> outputs,
                              List<List<TreeIngredient>> catalysts) {
    public long outputAmount(String target) {
        return outputs.stream().flatMap(List::stream).filter(i -> i.key().equals(target))
                .mapToLong(TreeIngredient::amount).min().orElse(Long.MAX_VALUE);
    }
    public TreeIngredient output(String target) {
        return outputs.stream().flatMap(List::stream).filter(i -> i.key().equals(target)).findFirst().orElseThrow();
    }
}
