package com.cti.recipeassistant.integration.ae2;

import appeng.client.gui.me.patternaccess.PatternAccessTermScreen;
import appeng.crafting.pattern.EncodedPatternItem;
import com.cti.recipeassistant.client.gui.CompactUi;
import com.cti.recipeassistant.client.operation.*;
import net.minecraft.client.gui.GuiGraphics;
import mezz.jei.common.Internal;
import mezz.jei.gui.elements.GuiIconButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.*;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** Native terminal entry and a decision-only popup. No inventory/provider management screen. */
public final class DistributionPreview {
    private static final int BUTTON_SIZE=13;
    private static final int LIST_TOP=49, SLOT=18;
    private static DistributionPreview open;
    private final PatternAccessTermScreen<?> terminal;
    private final PatternDistribution.Row row;
    private List<PatternDistribution.Choice> choices;
    private int offset;
    private boolean dirty;
    private DistributionPreview(PatternAccessTermScreen<?> terminal,PatternDistribution.Row row,List<PatternDistribution.Choice> choices){
        this.terminal=terminal;this.row=row;this.choices=choices;
    }
    public static boolean active(){return open!=null;}
    public static void choose(PatternAccessTermScreen<?> terminal,PatternDistribution.Row row,List<PatternDistribution.Choice> choices){
        if(open==null || open.terminal!=terminal || open.row!=row)open=new DistributionPreview(terminal,row,choices);
        else {open.choices=choices;open.offset=Math.min(open.offset,Math.max(0,choices.size()-open.visible()));open.dirty=false;}
    }
    public static void dismiss(){open=null;}
    public static void updated(PatternAccessTermScreen<?> terminal){if(open!=null && open.terminal==terminal)open.dirty=true;}
    private static int buttonX(PatternAccessTermScreen<?> terminal){return terminal.getGuiLeft()+175;}
    private static int buttonY(PatternAccessTermScreen<?> terminal){return terminal.getGuiTop()+3;}
    private static boolean available(){return !PatternDistributor.busy() && !CraftExecutor.busy() && !PatternEncoder.busy() && !SingleRecipeTransfer.busy();}
    private static GuiIconButton distributionButton(PatternAccessTermScreen<?> terminal) {
        var textures = Internal.getTextures();
        var button = new GuiIconButton(buttonX(terminal), buttonY(terminal), BUTTON_SIZE, BUTTON_SIZE,
                textures.getArrowNext(), ignored -> {
                    String error = PatternDistributor.start(terminal);
                    if (!error.isEmpty()) Minecraft.getInstance().gui.getChat().addMessage(Component.literal(error));
                });
        button.active = available();
        return button;
    }
    private static void renderTooltip(GuiGraphics graphics,List<Component> lines,int x,int y){
        graphics.pose().pushPose();graphics.pose().translate(0,0,-350);graphics.renderComponentTooltip(Minecraft.getInstance().font, lines,x,y);graphics.pose().popPose();
    }
    private int width(){return Math.min(340,terminal.width-16);}
    private int columns(){return Math.max(1,(width()-41)/SLOT);}
    private int rowHeight(){
        int installed=choices.stream().map(PatternDistribution.Choice::provider)
                .mapToInt(p->p.record().getInventory().size()-p.free()).max().orElse(0);
        int columns=columns();
        return 17+SLOT*Math.max(1,(installed+columns-1)/columns);
    }
    private int visible(){return Math.min(4,Math.max(1,(terminal.height-100)/rowHeight()));}
    private int height(){return 62+Math.max(1,Math.min(visible(),choices.size()))*rowHeight();}
    private int left(){return (terminal.width-width())/2;}
    private int top(){return (terminal.height-height())/2;}
    private void text(GuiGraphics graphics,String text,int x,int y,int width,int color){
        var font=Minecraft.getInstance().font;graphics.drawString(font, font.plainSubstrByWidth(text,Math.max(1,width)),x,y,color, false);
    }
    @SubscribeEvent public static void render(ScreenEvent.Render.Post event){
        if(!(event.getScreen() instanceof PatternAccessTermScreen<?> terminal))return;
        if(PatternDistributor.busy() && open==null)return;
        var graphics=event.getGuiGraphics();graphics.pose().pushPose();graphics.pose().translate(0,0,700);
        if(open==null){
            int x=buttonX(terminal),y=buttonY(terminal);boolean hover=CompactUi.hit(event.getMouseX(),event.getMouseY(),x,y,BUTTON_SIZE,BUTTON_SIZE);
            distributionButton(terminal).render(graphics,event.getMouseX(),event.getMouseY(),event.getPartialTick());
            if(hover)renderTooltip(graphics,List.of(Component.literal("自动分发")),event.getMouseX(),event.getMouseY());
        }else if(open.terminal==terminal){
            if(open.dirty)PatternDistributor.refreshChoices();
            if(open!=null)open.draw(graphics,event.getMouseX(),event.getMouseY());
        }
        graphics.pose().popPose();
    }
    private void draw(GuiGraphics graphics,int mx,int my){
        int l=left(),t=top(),w=width();CompactUi.panel(graphics,l,t,w,height());
        graphics.renderItem(row.pattern,l+8,t+9);
        text(graphics,row.name(),l+29,t+10,w-55,0x303030);text(graphics,"×",l+w-19,t+9,12,0x555555);
        text(graphics,row.reason.isEmpty()?row.machine():row.reason,l+9,t+31,w-18,row.reason.isEmpty()?0x555555:0x993333);
        int visible=visible(),rowHeight=rowHeight(),columns=columns();
        List<Component> tooltip=List.of();
        for(int n=0;n<visible;n++){
            int index=offset+n;if(index>=choices.size())break;
            var choice=choices.get(index);var provider=choice.provider();int y=t+LIST_TOP+n*rowHeight;
            var group=provider.record().getGroup();
            var inventory=provider.record().getInventory();
            boolean usable=provider.free()>0 || PatternDistribution.contains(provider,row.contents);
            boolean hover=CompactUi.hit(mx,my,l+7,y,w-14,rowHeight-3);
            graphics.fill(l+7,y,l+w-7,y+rowHeight-3,usable && hover?0xFFAAAAAA:0xFFB8B8B8);
            graphics.renderItem(group.icon().toStack(),l+10,y+4);
            String label="#"+(index+1)+" "+provider.name()+(row.routes.size()>1?" / "+choice.route().machine():"");
            text(graphics,label,l+31,y+4,w-43,usable?0x303030:0x555555);
            if(hover){
                tooltip=new ArrayList<>();
                tooltip.add(group.name());
                tooltip.add(Component.literal("空位 "+provider.free()+"/"+inventory.size()));
                tooltip.addAll(group.tooltip());
            }
            int shown=0;
            for(int slot=0;slot<inventory.size();slot++){
                var stack=inventory.getStackInSlot(slot);
                if(stack.isEmpty())continue;
                int px=l+31+shown%columns*SLOT,py=y+15+shown/columns*SLOT;
                var icon=stack.getItem() instanceof EncodedPatternItem pattern?pattern.getOutput(stack):stack;
                graphics.renderItem(icon.isEmpty()?stack:icon,px,py);
                if(CompactUi.hit(mx,my,px,py,16,16))tooltip=Screen.getTooltipFromItem(Minecraft.getInstance(), stack);
                shown++;
            }
        }
        if(choices.size()>visible){
            int h=visible*rowHeight,thumb=Math.max(8,h*visible/choices.size()),y=t+LIST_TOP+(h-thumb)*offset/(choices.size()-visible);
            graphics.fill(l+w-5,y,l+w-3,y+thumb,0xFF555555);
        }
        if(!tooltip.isEmpty())renderTooltip(graphics,tooltip,mx,my);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)public static void mouse(ScreenEvent.MouseButtonPressed.Pre event){
        if(!(event.getScreen() instanceof PatternAccessTermScreen<?> terminal))return;
        if(open!=null && open.terminal==terminal){
            if(open.dirty)PatternDistributor.refreshChoices();
            if(open!=null && event.getButton()==0){
                int l=open.left(),t=open.top(),w=open.width();double x=event.getMouseX(),y=event.getMouseY();
                if(CompactUi.hit(x,y,l+w-23,t+5,18,18))PatternDistributor.cancelSelection();
                else if(CompactUi.hit(x,y,l+7,t+LIST_TOP,w-14,open.visible()*open.rowHeight())){
                    int relative=(int)(y-t-LIST_TOP),rowHeight=open.rowHeight(),index=open.offset+relative/rowHeight;
                    if(index<open.choices.size() && relative%rowHeight<rowHeight-3)PatternDistributor.select(open.choices.get(index));
                }
            }
            event.setCanceled(true);return;
        }
        if(!PatternDistributor.busy() && event.getButton()==0 && CompactUi.hit(event.getMouseX(),event.getMouseY(),buttonX(terminal),buttonY(terminal),BUTTON_SIZE,BUTTON_SIZE)){
            distributionButton(terminal).mouseClicked(event.getMouseX(),event.getMouseY(),event.getButton());
            event.setCanceled(true);
        }
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)public static void scroll(ScreenEvent.MouseScrolled.Pre event){
        if(open==null || event.getScreen()!=open.terminal)return;
        if(CompactUi.hit(event.getMouseX(),event.getMouseY(),open.left()+7,open.top()+LIST_TOP,open.width()-14,open.visible()*open.rowHeight()))
            open.offset=Math.max(0,Math.min(Math.max(0,open.choices.size()-open.visible()),open.offset+(event.getScrollDelta()>0?-1:1)));
        event.setCanceled(true);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)public static void key(ScreenEvent.KeyPressed.Pre event){
        if(open==null || event.getScreen()!=open.terminal)return;
        if(event.getKeyCode()==GLFW.GLFW_KEY_ESCAPE || event.getKeyCode()==GLFW.GLFW_KEY_BACKSPACE)PatternDistributor.cancelSelection();
        event.setCanceled(true);
    }
    @SubscribeEvent public static void closing(ScreenEvent.Closing event){if(open!=null && event.getScreen()==open.terminal)PatternDistributor.cancelSelection();}
    @SubscribeEvent(priority=EventPriority.HIGHEST)public static void typed(ScreenEvent.CharacterTyped.Pre event){if(open!=null && event.getScreen()==open.terminal)event.setCanceled(true);}
    @SubscribeEvent(priority=EventPriority.HIGHEST)public static void drag(ScreenEvent.MouseDragged.Pre event){if(open!=null && event.getScreen()==open.terminal)event.setCanceled(true);}
    @SubscribeEvent(priority=EventPriority.HIGHEST)public static void release(ScreenEvent.MouseButtonReleased.Pre event){if(open!=null && event.getScreen()==open.terminal)event.setCanceled(true);}
}
