package com.cti.recipeassistant.integration.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AEProcessingPattern;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Actual AE2 data, independent of who encoded the pattern and local records. */
public record PatternContents(Kind kind, IPatternDetails details, List<GenericStack> inputs,
                              List<GenericStack> outputs, String identity) {
    public enum Kind { CRAFTING, PROCESSING, OTHER }
    public static PatternContents decode(ItemStack stack) {
        var decoded = PatternDetailsHelper.decodePattern(stack, Minecraft.getInstance().level);
        return decoded == null ? null : from(decoded);
    }
    static PatternContents from(IPatternDetails decoded) {
        var data = new CompoundTag();
        Kind kind;
        GenericStack[] inputs;
        if (decoded instanceof AECraftingPattern crafting) {
            kind = Kind.CRAFTING; inputs = crafting.getSparseInputs();
            data.putString("recipe", crafting.getDefinition().getTag().getString("recipe"));
            data.putBoolean("substitute", crafting.canSubstitute());
            data.putBoolean("substituteFluids", crafting.canSubstituteFluids());
        } else if (decoded instanceof AEProcessingPattern processing) {
            kind = Kind.PROCESSING; inputs = processing.getSparseInputs();
        } else {
            kind = Kind.OTHER;
            inputs = Arrays.stream(decoded.getInputs()).map(input -> {
                var first = input.getPossibleInputs()[0];
                return new GenericStack(first.what(), first.amount() * input.getMultiplier());
            }).toArray(GenericStack[]::new);
            // Other native pattern types retain their complete definition, without guessing rules.
            data.put("definition", decoded.getDefinition().toTagGeneric());
        }
        data.putString("type", decoded.getClass().getName());
        data.put("inputs", sparse(inputs)); data.put("outputs", sparse(decoded.getOutputs()));
        String identity = UUID.nameUUIDFromBytes(PatternRecordStore.canonical(data).getBytes(StandardCharsets.UTF_8)).toString();
        return new PatternContents(kind, decoded, Arrays.stream(inputs).filter(Objects::nonNull).toList(),
                List.of(decoded.getOutputs()), identity);
    }
    private static ListTag sparse(GenericStack[] stacks) {
        var list = new ListTag();
        for (var stack : stacks) list.add(stack == null ? new CompoundTag() : GenericStack.writeTag(stack));
        return list;
    }
    public String name() { return outputs.get(0).what().getDisplayName().getString(); }
}
