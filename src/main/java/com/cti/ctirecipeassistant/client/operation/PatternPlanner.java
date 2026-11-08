package com.cti.ctirecipeassistant.client.operation;

import com.cti.ctirecipeassistant.recipe.RecipeNode;
import java.util.*;

/** Selected patterns, independent of crafting order quantities. */
public final class PatternPlanner {
    public enum ProblemType { CYCLE, SAFETY, UNSUPPORTED }
    public record Issue(RecipeNode node, ProblemType type, String message, String path, long amount) {
        public Issue(RecipeNode node, ProblemType type, String message, String path) {
            this(node, type, message, path, node.target.amount());
        }
    }
    public record Plan(List<RecipeNode> steps, List<Issue> issues) {
        public List<String> problems() { return issues.stream().map(Issue::message).toList(); }
    }
    private PatternPlanner() {}
    public static Plan plan(RecipeNode root) { return plan(root, true); }
    public static Plan planEncoding(RecipeNode root) { return plan(root, false); }
    private static Plan plan(RecipeNode root, boolean loadProduction) {
        var steps = new LinkedHashMap<String, RecipeNode>();
        var issues = new ArrayList<Issue>();
        visit(root, steps, issues, new HashSet<>(), root.target.name(), new int[1], loadProduction);
        return new Plan(List.copyOf(steps.values()), mergeIssues(issues));
    }
    public static List<Issue> mergeIssues(List<Issue> source) {
        var merged = new LinkedHashMap<String, Issue>();
        for (var issue : source) {
            var node = issue.node();
            String recipe = node.selected == null
                    ? node.candidates.stream().map(c -> c.category() + ":" + c.id()).sorted().toList().toString()
                    : node.selected.category() + ":" + node.selected.id();
            String key = issue.type() + "|" + node.target.key() + "|" + recipe;
            if (issue.type() == ProblemType.CYCLE && node.parent != null && node.parent.selected != null)
                key += "|" + node.parent.selected.category() + ":" + node.parent.selected.id();
            // Unsupported causes can differ for one recipe; preserve their distinction.
            if (issue.type() == ProblemType.UNSUPPORTED) key += "|" + issue.message();
            var old = merged.get(key);
            if (old == null) merged.put(key, issue);
            else merged.put(key, new Issue(old.node(), old.type(), old.message(),
                    old.path().equals(issue.path()) || old.path().contains(" [另有依赖路径]")
                            ? old.path() : old.path() + " [另有依赖路径]", old.amount() + issue.amount()));
        }
        return merged.values().stream().map(i -> new Issue(i.node(), i.type(),
                i.message() + (i.amount() > 1 ? "（涉及 " + i.amount() + " 个）" : ""), i.path(), i.amount())).toList();
    }
    private static void visit(RecipeNode node, Map<String, RecipeNode> steps,
                              List<Issue> issues, Set<String> path, String label, int[] visited,
                              boolean loadProduction) {
        if (node.externalSupply || (node.selected == null && (!loadProduction || node.depth >= 64))) return;
        if (node.depth >= 64 || ++visited[0] > 4096) {
            issues.add(new Issue(node, ProblemType.SAFETY, node.target.name() + "：达到内部递归安全上限", label)); return;
        }
        if (loadProduction) node.load();
        String dependency = productionKey(node);
        if (node.cycle || !path.add(dependency)) {
            issues.add(new Issue(node, ProblemType.CYCLE, node.target.name() + "：生产路线存在循环", label)); return;
        }
        if (node.selected == null) {
            path.remove(dependency); return;
        }
        String key = node.selected.id() + "|" + node.target.key();
        for (var child : node.children) visit(child, steps, issues, path, label + " → " + child.target.name(), visited, loadProduction);
        steps.putIfAbsent(key, node);
        path.remove(dependency);
    }
    private static String productionKey(RecipeNode node) {
        return node.target.key() + "|" + (node.selected == null ? "unselected" : node.selected.category() + ":" + node.selected.id());
    }
}
