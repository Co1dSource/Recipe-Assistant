package com.cti.ctirecipeassistant.integration.ae2;

import com.cti.ctirecipeassistant.client.operation.*;
import appeng.client.gui.me.patternaccess.PatternAccessTermScreen;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.InventoryActionPacket;
import appeng.helpers.InventoryAction;
import com.cti.ctirecipeassistant.CtiRecipeAssistant;
import com.cti.ctirecipeassistant.integration.ae2.mixin.PatternAccessTermAccessor;
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
    private enum State { NEXT, PICKUP, UPLOAD, RETURN, PAUSED }
    private static PatternAccessTermScreen<?> terminal;
    private static List<PatternDistribution.Row> queue;
    private static List<Slot> playerSlots;
    private static State state;
    private static int index, completed, skipped, failures, ticks, source, remoteSlot, stateId, originalCount, destination;
    private static boolean cancel;
    private static String message="";
    private static ItemStack beforeReturn=ItemStack.EMPTY, returnStack=ItemStack.EMPTY;
    private PatternDistributor() {}
    public static boolean busy(){return terminal!=null;}
    public static String start(PatternAccessTermScreen<?> screen,List<PatternDistribution.Row> rows) {
        var mc=Minecraft.getInstance();
        if(busy() || PatternEncoder.busy() || CraftExecutor.busy() || SingleRecipeTransfer.busy())return "已有物品操作正在执行";
        if(mc.screen!=screen || mc.player==null || mc.player.containerMenu!=screen.getMenu()
                || !screen.getMenu().isValidMenu())return "终端已失效";
        if(!screen.getMenu().getCarried().isEmpty())return "请先放下光标物品";
        queue=rows.stream().filter(r->r.upload>0).toList();
        if(queue.isEmpty())return "没有可上传样板，请配置映射或清理槽位";
        terminal=screen;playerSlots=PatternDistribution.playerSlots(screen);
        index=0;completed=0;failures=0;skipped=rows.stream().mapToInt(r->r.quantity-r.upload).sum();
        cancel=false;state=State.NEXT;message="";return "";
    }
    private static PatternDistribution.Row current(){return queue.get(index);}
    private static boolean targetValid() {
        var expected=current().provider;
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
        if(state==State.PAUSED)return;
        if(state==State.NEXT) {
            if(cancel){finish("已取消");return;}
            if(!targetValid()){failures++;finish("目标供应器或机器分组已变化，请重新预览");return;}
            if(PatternDistribution.contains(current().provider,current().pattern)) {
                if (current().records.size()>1 && !current().confirmedDuplicate.equals(current().route()+"|"+current().provider.id())) {
                    failures++;finish("目标新增同内容样板，类别未确认，请重新预览");return;
                }
                skipped++;advance();return;
            }
            remoteSlot=PatternDistribution.emptySlot(current().provider);
            if(remoteSlot<0){skipped++;message="供应器需要 1 槽，剩余 0，样板保留";advance();return;}
            var supply=playerSlots.stream().filter(s->ItemStack.isSameItemSameTags(s.getItem(),current().pattern)).findFirst();
            if(supply.isEmpty()){failures++;finish("待上传样板已不在玩家物品栏");return;}
            source=supply.get().index;originalCount=supply.get().getItem().getCount();
            click(supply.get());state=State.PICKUP;return;
        }
        if(state==State.PICKUP && menu.getStateId()!=stateId && carried(originalCount) && !menu.getSlot(source).hasItem()) {
            if(cancel || !targetValid() || !current().provider.record().getInventory().getStackInSlot(remoteSlot).isEmpty()
                    || PatternDistribution.contains(current().provider,current().pattern)) {
                cancel=true;message="已取消或目标状态变化";returnCarried();return;
            }
            // The native right click places exactly one item from a stacked cursor.
            var action=originalCount==1?InventoryAction.PICKUP_OR_SET_DOWN:InventoryAction.SPLIT_OR_PLACE_SINGLE;
            NetworkHandler.instance().sendToServer(new InventoryActionPacket(action,remoteSlot,current().provider.id()));
            ticks=0;state=State.UPLOAD;return;
        }
        if(state==State.UPLOAD && targetValid() && carried(originalCount-1)
                && ItemStack.matches(current().provider.record().getInventory().getStackInSlot(remoteSlot),current().pattern)) {
            completed++;
            if(originalCount>1)returnCarried();else advance();
            return;
        }
        if(state==State.RETURN && menu.getStateId()!=stateId && menu.getCarried().isEmpty()) {
            var actual=menu.getSlot(destination).getItem();
            if(ItemStack.isSameItemSameTags(actual,returnStack)
                    && actual.getCount()==beforeReturn.getCount()+returnStack.getCount()) {
                if(cancel)finish(message.isEmpty()?"已取消":message);else advance();
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
    private static void advance() {
        if(cancel){finish(message.isEmpty()?"已取消":message);return;}
        index++;
        if(index==queue.size())finish(message.isEmpty()?"分发完成":message);
        else {state=State.NEXT;ticks=0;}
    }
    private static void finish(String reason) {
        var mc=Minecraft.getInstance();
        if(mc.player!=null)mc.gui.getChat().addMessage(Component.literal("自动分发："+reason+"；成功 "+completed+"，跳过 "+skipped+"，失败 "+failures));
        terminal=null;queue=null;
    }
    @SubscribeEvent
    public static void render(ScreenEvent.Render.Post event) {
        if(!busy())return;
        var pose=event.getPoseStack();var mc=Minecraft.getInstance();var screen=event.getScreen();
        pose.pushPose();pose.translate(0,0,750);
        Screen.fill(pose,0,0,screen.width,40,0xEF303030);
        String text=current().machine()+" / "+current().provider.type()+"  成功 "+completed+"/"+queue.size();
        Screen.drawString(pose,mc.font,mc.font.plainSubstrByWidth(text,Math.max(1,screen.width-75)),5,6,0xFFFFFF);
        Screen.drawString(pose,mc.font,cancel?"正在取消":"取消",screen.width-72,6,0xE5B989);
        Screen.drawString(pose,mc.font,mc.font.plainSubstrByWidth(state==State.PAUSED?message:"等待同步",Math.max(1,screen.width-65)),5,25,0xB9C0CB);
        if(state==State.PAUSED)Screen.drawString(pose,mc.font,"继续",screen.width-45,25,0xE5B989);
        pose.popPose();
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void mouse(ScreenEvent.MouseButtonPressed.Pre event) {
        if(!busy())return;
        if(event.getMouseY()<40 && event.getMouseX()>=event.getScreen().width-80) {
            if(event.getMouseY()<20)cancel=true;
            else if(state==State.PAUSED && terminal.getMenu().getCarried().isEmpty()) {
                if(cancel)finish("已取消");else advance();
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
        if(!busy())return;
        if(event.getKeyCode()==GLFW.GLFW_KEY_BACKSPACE)cancel=true;
        if(event.getKeyCode()!=GLFW.GLFW_KEY_ESCAPE)event.setCanceled(true);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void typed(ScreenEvent.CharacterTyped.Pre event) {if(busy())event.setCanceled(true);}
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void scroll(ScreenEvent.MouseScrolled.Pre event) {if(busy())event.setCanceled(true);}
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void drag(ScreenEvent.MouseDragged.Pre event) {if(busy())event.setCanceled(true);}
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void release(ScreenEvent.MouseButtonReleased.Pre event) {if(busy())event.setCanceled(true);}

}
