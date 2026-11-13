package com.cti.recipeassistant.integration.ae2;

import com.cti.recipeassistant.client.operation.*;
import appeng.client.gui.me.patternaccess.PatternAccessTermScreen;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.InventoryActionPacket;
import appeng.helpers.InventoryAction;
import com.cti.recipeassistant.RecipeAssistant;
import com.cti.recipeassistant.recipe.RecipePreferences;
import com.cti.recipeassistant.integration.ae2.mixin.PatternAccessTermAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import java.util.*;

public final class PatternDistributor {
    private enum State { NEXT, SELECTING, PICKUP, UPLOAD, RETURN, PAUSED }
    private static PatternAccessTermScreen<?> terminal;
    private static List<PatternDistribution.Row> queue;
    private static List<Slot> playerSlots;
    private static State state;
    private static int index, completed, skipped, failures, ticks, source, remoteSlot, stateId, originalCount, destination;
    private static boolean cancel, retryCurrent;
    private static RecipePreferences preferences;
    private static final Map<String, PatternDistribution.Choice> batch = new HashMap<>();
    private static String message="";
    private static ItemStack beforeReturn=ItemStack.EMPTY, returnStack=ItemStack.EMPTY;
    private PatternDistributor() {}
    public static boolean busy(){return terminal!=null;}
    public static String start(PatternAccessTermScreen<?> screen) {
        var mc=Minecraft.getInstance();
        if(busy() || PatternEncoder.busy() || CraftExecutor.busy() || SingleRecipeTransfer.busy())return "已有物品操作正在执行";
        if(mc.screen!=screen || mc.player==null || mc.player.containerMenu!=screen.getMenu()
                || !screen.getMenu().isValidMenu())return "终端已失效";
        if(!screen.getMenu().getCarried().isEmpty())return "请先放下光标物品";
        preferences=new RecipePreferences();
        queue=PatternDistribution.rows(screen,preferences);
        if(queue.isEmpty())return "没有可分发样板";
        terminal=screen;playerSlots=PatternDistribution.playerSlots(screen);
        index=0;completed=0;failures=0;skipped=0;batch.clear();retryCurrent=false;
        cancel=false;state=State.NEXT;message="";return "";
    }
    private static PatternDistribution.Row current(){return queue.get(index);}
    private static boolean targetValid() {
        var expected=current().provider();
        var record=((PatternAccessTermAccessor)terminal).cti$getProviders().get(expected.id());
        return record!=null && record==expected.record()
                && PatternDistribution.provider(record).key().equals(expected.key());
    }
    private static boolean carried(int count) {
        var actual=terminal.getMenu().getCarried();
        return count==0?actual.isEmpty():actual.getCount()==count && ItemStack.isSameItemSameTags(actual,current().pattern);
    }
    private static void click(Slot slot) {
        var menu=terminal.getMenu();stateId=menu.getStateId();ticks=0;
        Minecraft.getInstance().getConnection().send(new ServerboundContainerClickPacket(menu.containerId,stateId,
                slot.index,0,ClickType.PICKUP,menu.getCarried().copy(),new Int2ObjectOpenHashMap<>()));
    }
    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END || !busy())return;
        var mc=Minecraft.getInstance();var menu=terminal.getMenu();
        if(mc.player==null || mc.player.containerMenu!=menu || mc.screen!=terminal || !menu.isValidMenu()) {
            finish("终端会话失效，已停止；保留服务器实际物品状态");return;
        }
        if(state==State.SELECTING){if(cancel)finish("已取消");return;}
        if(state==State.PAUSED){if(cancel)finish("已取消，光标样板保留");return;}
        if(state==State.NEXT) {
            if(cancel){finish("已取消");return;}
            if(current().contents==null){failures++;message=current().reason;advance();return;}
            var choices=PatternDistribution.resolve(current(),PatternDistribution.providers(terminal),preferences,batch,false);
            if(current().choice==null){
                if(choices.isEmpty()){failures++;message=current().reason;advance();return;}
                state=State.SELECTING;DistributionPreview.choose(terminal,current(),choices);return;
            }
            boolean crafting = current().contents.kind() == PatternContents.Kind.CRAFTING;
            if (!crafting) PatternDistribution.remember(current(),current().choice,preferences,batch,false);
            if(PatternDistribution.contains(current().provider(),current().contents)){skipped++;advance();return;}
            remoteSlot=PatternDistribution.emptySlot(current().provider());
            if(remoteSlot<0){
                if(!crafting){state=State.SELECTING;refreshChoices();}
                return;
            }
            var supply=playerSlots.stream().filter(s->s.mayPickup(mc.player) && ItemStack.isSameItemSameTags(s.getItem(),current().pattern)).findFirst();
            if(supply.isEmpty()){failures++;finish("待上传样板已不在玩家物品栏");return;}
            source=supply.get().index;originalCount=supply.get().getItem().getCount();
            click(supply.get());state=State.PICKUP;return;
        }
        if(state==State.PICKUP && menu.getStateId()!=stateId && carried(originalCount) && !menu.getSlot(source).hasItem()) {
            if(cancel || !targetValid() || !current().provider().record().getInventory().getStackInSlot(remoteSlot).isEmpty()
                    || PatternDistribution.contains(current().provider(),current().contents)) {
                retryCurrent=!cancel;message=cancel?"已取消":"目标状态已变化";returnCarried();return;
            }
            // The native right click places exactly one item from a stacked cursor.
            var action=originalCount==1?InventoryAction.PICKUP_OR_SET_DOWN:InventoryAction.SPLIT_OR_PLACE_SINGLE;
            NetworkHandler.instance().sendToServer(new InventoryActionPacket(action,remoteSlot,current().provider().id()));
            ticks=0;state=State.UPLOAD;return;
        }
        if(state==State.UPLOAD && targetValid() && carried(originalCount-1)
                && ItemStack.matches(current().provider().record().getInventory().getStackInSlot(remoteSlot),current().pattern)) {
            completed++;
            if(originalCount>1)returnCarried();else advance();
            return;
        }
        if(state==State.RETURN && menu.getStateId()!=stateId && menu.getCarried().isEmpty()) {
            var actual=menu.getSlot(destination).getItem();
            if(ItemStack.isSameItemSameTags(actual,returnStack)
                    && actual.getCount()==beforeReturn.getCount()+returnStack.getCount()) {
                afterReturn();
                return;
            }
        }
        if(++ticks>=100) {
            failures++;
            finish("等待服务器同步超时，未重发；未上传样板保留，光标物品请手动收回");
        }
    }
    private static void returnCarried() {
        var menu=terminal.getMenu();returnStack=menu.getCarried().copy();
        var candidates=new ArrayList<>(playerSlots);
        candidates.sort(Comparator.comparing(s->s.index!=source));
        for(var slot:candidates) {
            var stack=slot.getItem();
            if(slot.mayPlace(returnStack) && (stack.isEmpty() || ItemStack.isSameItemSameTags(stack,returnStack))
                    && stack.getCount()+returnStack.getCount()<=Math.min(slot.getMaxStackSize(returnStack),returnStack.getMaxStackSize())) {
                destination=slot.index;beforeReturn=stack.copy();click(slot);state=State.RETURN;return;
            }
        }
        state=State.PAUSED;message="物品栏没有接收空间，请手动收回光标样板后点继续";
    }
    public static void refreshChoices() {
        if(!busy() || state!=State.SELECTING)return;
        var choices=PatternDistribution.resolve(current(),PatternDistribution.providers(terminal),preferences,batch,true);
        DistributionPreview.choose(terminal,current(),choices);
    }
    public static void select(PatternDistribution.Choice selected) {
        if(!busy() || state!=State.SELECTING)return;
        var choices=PatternDistribution.resolve(current(),PatternDistribution.providers(terminal),preferences,batch,true);
        var fresh=choices.stream().filter(c->c.provider().id()==selected.provider().id()
                && c.provider().key().equals(selected.provider().key()) && c.route().equals(selected.route())).findFirst();
        if(fresh.isEmpty() || PatternDistribution.emptySlot(fresh.get().provider())<0
                && !PatternDistribution.contains(fresh.get().provider(),current().contents)){refreshChoices();return;}
        PatternDistribution.remember(current(),fresh.get(),preferences,batch,true);
        DistributionPreview.dismiss();state=State.NEXT;
    }
    public static void cancelSelection(){if(busy() && state==State.SELECTING)finish("已取消");}
    private static void afterReturn(){
        if(cancel)finish("已取消");
        else if(retryCurrent){retryCurrent=false;state=State.NEXT;}
        else advance();
    }
    private static void advance() {
        if(cancel){finish(message.isEmpty()?"已取消":message);return;}
        skipped+=Math.max(0,current().quantity-1);
        index++;
        if(index==queue.size())finish(message.isEmpty()?"分发完成":message);
        else {state=State.NEXT;ticks=0;}
    }
    private static void finish(String reason) {
        var mc=Minecraft.getInstance();
        if(mc.player!=null)mc.gui.getChat().addMessage(Component.literal("已分发 "+completed+"，跳过 "+skipped+(failures>0?"，失败 "+failures:"")+(!reason.equals("分发完成")?"；"+reason:"")));
        DistributionPreview.dismiss();terminal=null;queue=null;preferences=null;batch.clear();
    }
    @SubscribeEvent
    public static void render(ScreenEvent.Render.Post event) {
        if(!busy() || state==State.SELECTING)return;
        var graphics=event.getGuiGraphics();var mc=Minecraft.getInstance();var screen=event.getScreen();
        graphics.pose().pushPose();graphics.pose().translate(0,0,750);
        graphics.fill(screen.width-55,0,screen.width,20,0xEF303030);
        graphics.drawString(mc.font,cancel?"正在取消":"取消",screen.width-50,6,0xE5B989);
        if(state==State.PAUSED){
            graphics.fill(0,20,screen.width,40,0xEF303030);
            graphics.drawString(mc.font,mc.font.plainSubstrByWidth(message,Math.max(1,screen.width-65)),5,25,0xB9C0CB);
            graphics.drawString(mc.font,"继续",screen.width-45,25,0xE5B989);
        }
        graphics.pose().popPose();
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void mouse(ScreenEvent.MouseButtonPressed.Pre event) {
        if(!busy() || state==State.SELECTING)return;
        if(event.getMouseY()<(state==State.PAUSED?40:20) && event.getMouseX()>=event.getScreen().width-80) {
            if(event.getMouseY()<20)cancel=true;
            else if(state==State.PAUSED && terminal.getMenu().getCarried().isEmpty()) {
                afterReturn();
            }
            event.setCanceled(true);return;
        }
        if(state==State.PAUSED) {
            double x=event.getMouseX()-terminal.getGuiLeft(),y=event.getMouseY()-terminal.getGuiTop();
            if(playerSlots.stream().anyMatch(s->x>=s.x && x<s.x+16 && y>=s.y && y<s.y+16))return;
        }
        event.setCanceled(true);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void key(ScreenEvent.KeyPressed.Pre event) {
        if(!busy() || state==State.SELECTING)return;
        if(event.getKeyCode()==GLFW.GLFW_KEY_BACKSPACE)cancel=true;
        if(event.getKeyCode()!=GLFW.GLFW_KEY_ESCAPE)event.setCanceled(true);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void typed(ScreenEvent.CharacterTyped.Pre event) {if(busy() && state!=State.SELECTING)event.setCanceled(true);}
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void scroll(ScreenEvent.MouseScrolled.Pre event) {if(busy() && state!=State.SELECTING)event.setCanceled(true);}
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void drag(ScreenEvent.MouseDragged.Pre event) {if(busy() && state!=State.SELECTING)event.setCanceled(true);}
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void release(ScreenEvent.MouseButtonReleased.Pre event) {if(busy() && state!=State.SELECTING)event.setCanceled(true);}

}
