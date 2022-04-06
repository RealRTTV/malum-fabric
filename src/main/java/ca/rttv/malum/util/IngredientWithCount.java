package ca.rttv.malum.util;

import ca.rttv.malum.recipe.SpiritInfusionRecipe;
import com.google.gson.*;
import net.minecraft.item.Item;
import net.minecraft.item.ItemConvertible;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.tag.TagKey;
import net.minecraft.util.Holder;
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class IngredientWithCount implements Predicate<ItemStack> {
    public static final IngredientWithCount EMPTY = new IngredientWithCount(Stream.empty());

    private final IngredientWithCount.Entry[] entries;
    private ItemStack[] matchingStacks;

    public IngredientWithCount(Stream<? extends IngredientWithCount.Entry> stream) {
        this.entries = stream.toArray(Entry[]::new);
    }

    public ItemStack[] getMatchingStacks() {
        this.cacheMatchingStacks();
        return matchingStacks;
    }

    public IngredientWithCount.Entry[] getEntries() {
        return this.entries;
    }

    @Override
    public boolean test(@Nullable ItemStack stack) {
        if (stack == null) {
            return false;
        } else {
            this.cacheMatchingStacks();
            if (this.matchingStacks.length == 0) {
                return stack.isEmpty();
            } else {
                for(ItemStack itemStack2 : this.matchingStacks) {
                    if (itemStack2.isOf(stack.getItem())) {
                        return true;
                    }
                }

                return false;
            }
        }
    }

    public static IngredientWithCount fromJson(@Nullable JsonElement json) {
        if (json != null && !json.isJsonNull()) {
            if (json.isJsonObject()) {
                return ofEntries(Stream.of(entryFromJson(json.getAsJsonObject())));
            } else if (json.isJsonArray()) {
                JsonArray jsonArray = json.getAsJsonArray();
                if (jsonArray.size() == 0) {
                    return EMPTY;
                } else {
                    return ofEntries(StreamSupport.stream(jsonArray.spliterator(), false).map(jsonElement -> entryFromJson(JsonHelper.asObject(jsonElement, "item"))));
                }
            } else {
                throw new JsonSyntaxException("Expected item to be object or array of objects");
            }
        } else {
            throw new JsonSyntaxException("Item cannot be null");
        }
    }

    private static IngredientWithCount ofEntries(Stream<? extends IngredientWithCount.Entry> entries) {
        IngredientWithCount ingredient = new IngredientWithCount(entries);
        return ingredient.entries.length == 0 ? EMPTY : ingredient;
    }

    private static IngredientWithCount.Entry entryFromJson(JsonObject json) {
        if (json.has("item") && json.has("tag")) {
            throw new JsonParseException("An ingredient entry is either a tag or an item, not both");
        } else if (json.has("item")) {
            Item item = ShapedRecipe.getItem(json);
            int count = JsonHelper.getInt(json, "count", 1);
            return new IngredientWithCount.StackEntry(new ItemStack(item, count));
        } else if (json.has("tag")) {
            Identifier identifier = new Identifier(JsonHelper.getString(json, "tag"));
            TagKey<Item> tagKey = TagKey.of(Registry.ITEM_KEY, identifier);
            int count = JsonHelper.getInt(json, "count", 1);
            return new IngredientWithCount.TagEntry(tagKey, count);
        } else {
            throw new JsonParseException("An ingredient entry needs either a tag or an item");
        }
    }

    private void cacheMatchingStacks() {
        if (this.matchingStacks == null) {
            this.matchingStacks = Arrays.stream(this.entries).flatMap(entry -> entry.getStacks().stream()).distinct().toArray(ItemStack[]::new);
        }
    }

    public void write(PacketByteBuf buf) {
        this.cacheMatchingStacks();
        buf.writeCollection(Arrays.asList(this.matchingStacks), PacketByteBuf::writeItemStack);
    }

    public JsonElement toJson() {
        if (this.entries.length == 1) {
            return this.entries[0].toJson();
        } else {
            JsonArray jsonArray = new JsonArray();

            for(IngredientWithCount.Entry entry : this.entries) {
                jsonArray.add(entry.toJson());
            }

            return jsonArray;
        }
    }

    public boolean isEmpty() {
        return this.entries.length == 0 && (this.matchingStacks == null || this.matchingStacks.length == 0);
    }

    public static IngredientWithCount empty() {
        return EMPTY;
    }

    public static IngredientWithCount ofItems(ItemConvertible... items) {
        return ofStacks(Arrays.stream(items).map(ItemStack::new));
    }

    public static IngredientWithCount ofStacks(ItemStack... stacks) {
        return ofStacks(Arrays.stream(stacks));
    }

    public static IngredientWithCount ofStacks(Stream<ItemStack> stacks) {
        return ofEntries(stacks.filter(stack -> !stack.isEmpty()).map(IngredientWithCount.StackEntry::new));
    }

    public static IngredientWithCount of(TagKey<Item> tagKey, int count) {
        return ofEntries(Stream.of(new IngredientWithCount.TagEntry(tagKey, count)));
    }

    public static IngredientWithCount fromPacket(PacketByteBuf buf) {
        return ofEntries(buf.readList(PacketByteBuf::readItemStack).stream().map(IngredientWithCount.StackEntry::new));
    }

    public Ingredient asIngredient() {
        return Ingredient.fromJson(this.toJson());
    }

    public interface Entry {
        List<ItemStack> getStacks();

        JsonObject toJson();

        boolean isValidItem(ItemStack stack);
    }

    public static final class StackEntry implements Entry {

        public final ItemStack stack;

        StackEntry(ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public List<ItemStack> getStacks() {
            return List.of(stack);
        }

        @Override
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("item", Registry.ITEM.getId(stack.getItem()).toString());
            json.addProperty("count", stack.getCount());
            return json;
        }

        @Override
        public boolean isValidItem(ItemStack stack) {
            return this.stack.getCount() == stack.getCount() && this.stack.getItem() == stack.getItem();
        }
    }

    public static final class TagEntry implements Entry {
        private final TagKey<Item> tag;
        private final int count;

        TagEntry(TagKey<Item> tag, int count) {
            this.tag = tag;
            this.count = count;
        }

        @Override
        public List<ItemStack> getStacks() {
            List<ItemStack> stacks = new ArrayList<>();

            for (Holder<Item> item : Registry.ITEM.getTagOrEmpty(tag)) {
                stacks.add(new ItemStack(item.value(), count));
            }

            return stacks;
        }

        @Override
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("tag", this.tag.id().toString());
            json.addProperty("count", count);
            return json;
        }

        @Override
        public boolean isValidItem(ItemStack stack) {
            return stack.streamTags().anyMatch(tag -> tag == this.tag);
        }
    }
}
