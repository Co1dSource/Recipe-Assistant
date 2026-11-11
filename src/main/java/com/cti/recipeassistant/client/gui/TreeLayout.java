package com.cti.recipeassistant.client.gui;

import com.cti.recipeassistant.recipe.RecipeNode;
import java.util.*;

/** Left-to-right leaf spacing and centered parents, following Quick Craft's TreeLayout. */
public final class TreeLayout {
    public static final int WIDTH = 164, HEIGHT = 46, H_GAP = 44, V_GAP = 12;
    public record NodeView(RecipeNode node, int x, int y) {}
    public final List<NodeView> views = new ArrayList<>();
    public final Map<RecipeNode, NodeView> byNode = new IdentityHashMap<>();
    private int nextLeafY;
    public TreeLayout(RecipeNode root) { place(root, 0); }
    private NodeView place(RecipeNode node, int depth) {
        int y;
        if (!node.expanded || node.children.isEmpty()) {
            y = nextLeafY;
            nextLeafY += HEIGHT + V_GAP;
        } else {
            int first = -1, last = 0;
            for (var child : node.children) {
                var view = place(child, depth + 1);
                if (first < 0) first = view.y();
                last = view.y();
            }
            y = (first + last) / 2;
        }
        var view = new NodeView(node, depth * (WIDTH + H_GAP), y);
        views.add(view);
        byNode.put(node, view);
        return view;
    }
}
