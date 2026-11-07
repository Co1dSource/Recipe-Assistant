# CTI Recipe Assistant — 阶段 0 API 核验报告

状态：**Chat 阶段 0 已完成**。本报告供 Codex 阶段 1～8 直接使用；不要重复调查已确认事实。

## 1. 目标版本

CTI 整合包 989 的公开模组清单列出：

- Minecraft 1.19.2
- Forge 43.4.2
- Java 17
- JEI 11.6.0.1024
- Applied Energistics 2 12.9.9
- ExtendedAE 1.19-0.2.2-forge

版本来源：<https://www.mcmod.cn/modpack/989.html>。JEI 文件记录：<https://www.curseforge.com/minecraft/mc-mods/jei/files/5543121>。

**边界**：用户尚未上传本地 JAR，因此没有对本地文件名、哈希或 manifest 做直接核验。开发基准按整合包公开清单固定；若 `reference/mods` 中的本地 JAR 与清单不同，以实际 JAR/启动日志为准，不自动升级。

Java 17 也与 ExtendedAE 1.19 分支 `build.gradle` 中的 `java.toolchain.languageVersion = JavaLanguageVersion.of(17)` 一致。

## 2. Quick Craft（MC 百科 #30500，对应 Sxilverrr）

源码：<https://github.com/Sxilverrr/Quick-Craft>  
许可证：MIT，复制代码需随项目保留版权与 LICENSE。

已核对：
- 当前主干的版本目录只有 `1.20.1` 和 `1.21.1`，没有 1.19.2 分支。
- `TreeLayout.java`、`NodeView.java` 是适合参考/移植的树布局基础。
- `RecipePreferences.java` 可参考其本地偏好保存方式。
- `QuickCraftScreen.java` 约 2200 行，依赖站点、EMC、配方规划、服务器预览等逻辑；1.20.1+ 使用 `GuiGraphics`，不能整文件复制到 1.19.2。
- `TreeBuilder`/`RecipeResolver` 主要基于 Minecraft `RecipeManager` 与 Quick Craft 自己的 `StationRules`，不满足本项目“以 JEI 注册配方为准”的要求。

结论：复用/改写布局和交互思路；配方索引、RecipeNode 数据模型和 1.19.2 GUI 自己实现。不要移植 `CraftService`、网络包、附近容器、EMC、StationScan、CraftPlanner。

## 3. JEI 11.6.0.1024

源码标签：<https://github.com/mezz/JustEnoughItems/tree/v11.6.0>

### 配方树读取

已确认的公开接口：
- `IJeiRuntime#getRecipeManager()`
- `IRecipeManager#createRecipeCategoryLookup()`
- `IRecipeCategoriesLookup#limitFocus(...)`
- `IRecipeManager#createRecipeLookup(recipeType)`
- `IRecipeLookup#limitFocus(...)`
- `IRecipeManager#createRecipeLayoutDrawable(category, recipe, focusGroup)`
- `IRecipeLayoutDrawable#getRecipe()`
- `IRecipeLayoutDrawable#getRecipeCategory()`
- `IRecipeLayoutDrawable#getRecipeSlotsView()`
- `IRecipeLayoutDrawable#getRect()`
- `IRecipeSlotsView#getSlotViews(RecipeIngredientRole)`
- `IRecipeSlotView#getAllIngredients()` / `getItemStacks()`

配方树查询方式：对目标物品建立 `OUTPUT` focus，查有匹配配方的类别，再逐类别查 recipe lookup；对每条 recipe 建立 layout drawable，从 `INPUT`、`OUTPUT`、`CATALYST` 读取槽位。这样读取 JEI 注册的机器/KubeJS 配方，不局限于原版 RecipeManager。

`IRecipeSlotView` 暴露 ingredient 候选和类型，但 JEI 11.6 没有通用概率权重字段。可以把所有输出候选作为目标选择项；不从 JEI 通用接口推断概率，不自动做概率换算。

### JEI 逐配方右上角按钮

- `IRecipeLayoutDrawable` 提供当前 recipe、category、slot view 和 rect。
- `RecipesGui` 内部有私有 `recipeLayouts` 和 `parentScreen` 字段；`IRecipesGui` 没有公开的当前布局列表 getter。
- 用两个只读 Mixin Accessor 读取这些字段；Forge `ScreenEvent` 负责绘制每张配方右上角的按钮、tooltip 和点击。
- 原版 JEI transfer 图标在 `assets/jei/textures/gui/icons/recipe_transfer.png`，JEI `Textures` 中以 7×7 注册；按钮点击区域可沿用 13×13。
- 不修改 JEI JAR，不替换 JEI 原右下角按钮。

### JEI 配方填入

已确认公开接口：
- `IJeiRuntime#getRecipeTransferManager()`
- `IRecipeTransferManager#getRecipeTransferHandler(container, recipeCategory)`
- `IRecipeTransferHandler#transferRecipe(container, recipe, recipeSlots, player, maxTransfer, doTransfer)`

`doTransfer=false` 用于检查，`doTransfer=true` 执行。JEI 自己的 transfer button 也是调用对应 transfer handler。阶段 4 只在原版 3×3 `CraftingMenu` 上验证一条配方，之后阶段 5 才连续执行。

## 4. AE2 12.9.9 样板编码

源码：<https://github.com/AppliedEnergistics/Applied-Energistics-2/tree/forge/v12.9.9>

### 现有 JEI 编码处理器

- `JEIPlugin#registerRecipeTransferHandlers` 注册 `EncodePatternTransferHandler` 为 `PatternEncodingTermMenu` 的 universal transfer handler。
- `EncodePatternTransferHandler#transferRecipe(..., doTransfer=true)` 对工作台/切石/锻造配方调用 `EncodingHelper.encodeCraftingRecipe`；其他配方调用 `EncodingHelper.encodeProcessingRecipe`。
- `EncodingHelper` 通过 AE2 现有 `InventoryActionPacket(SET_FILTER, ...)` 填充 `PatternEncodingTermMenu` 的 FakeSlot。
- **该 JEI transfer handler 只负责填入配方，不负责最终编码。** 之后必须调用 `PatternEncodingTermMenu#encode()`；客户端方法通过 AE2 注册的 `ACTION_ENCODE` 交给服务器，服务器使用 `PatternDetailsHelper` 编码并检查空白样板/输出槽。

### 手动原料与概率数量

- 处理样板路径从 `IRecipeSlotsView` 通过 `GenericEntryStackHelper.ofInputs/ofOutputs` 转成 `GenericStack`；输入候选可以在调用 transfer handler 前通过包装 `IRecipeSlotsView` 缩窄，输入数量也可通过转换后的 stack 数量传入。
- AE2 默认按 ME 网络/玩家库存优先级从候选中选择原料。用户已手动选择的输入必须限制候选，不能让默认库存优先级覆盖选择。
- 对工作台 `Recipe<?>`，AE2 的 `EncodingHelper.encodeCraftingRecipe` 会按原 recipe ingredients 从网络优先级选材料；若要强制用户选择的具体物品，使用 AE2 已有 `SET_FILTER` 槽操作覆盖对应 FakeSlot，不改 NBT。
- `GenericEntryStackHelper.ofOutputs` 每个 JEI 输出槽取第一个候选。编码某个副产物目标时，传入的 slot view 应只保留用户选定的目标输出；其他输出忽略。概率输入数量由用户手动填，目标输出数量保持不变。

## 5. AE2 / ExtendedAE 样板管理终端与自动分发

**游戏兼容性已由用户实测**：AE2 原版样板管理终端可以控制普通样板供应器、ExtendedAE 扩展样板供应器和连接的分子装配室。阶段 7 不再重复验证基本兼容性。

源码确认：
- AE2 `PatternProviderLogic` 默认构造容量为 9。
- ExtendedAE `TileExPatternProvider` 和 `PartExPatternProvider` 使用 `new PatternProviderLogic(..., 36)`。
- `PatternAccessTermMenu` 服务端为每个 `PatternContainer` 建立会话 `serverId`，发送完整/增量供应器库存更新。
- `PatternAccessTermScreen` 私有 `byId` 保存客户端 `PatternContainerRecord`；用最小只读 Mixin Accessor 读取全部当前记录。
- `PatternContainerRecord` 公开 `getServerId()`、`getInventory()`、`getGroup()`；库存大小是 9 或 36；`PatternContainerGroup` 提供 icon/name/tooltip。
- 分组信息不包含机器方块坐标或唯一 registry ID；匹配以 group icon/name/tooltip 为准，歧义时人工选择。
- AE2 原版屏幕点击 `PatternSlot` 时发送 `InventoryActionPacket(action, slot, serverId)`。服务端 `PatternAccessTermMenu#doAction` 使用会话 ID 定位库存并执行 `PICKUP_OR_SET_DOWN` 等动作；插入过滤只接受 `EncodedPatternItem`。
- 服务端改动后通过 `PatternAccessTerminalPacket` 发增量更新，客户端 `PatternAccessTermScreen#postIncrementalUpdate` 更新 `PatternContainerRecord` 的库存。
- 因此自动分发直接复用 AE2 现有菜单和 packet；不要创建本模组自定义服务器 packet，不要直接写远程库存。`serverId` 只在当前终端会话使用，不持久化。

## 6. 阶段 0 之后的真实待验证项

1. 本地 JAR 文件版本/哈希与 CTI 公开清单是否一致（本次未收到本地 JAR）。
2. JEI transfer handler 在实际 CTI 多人服务器中连续执行后，工作台输入、结果槽与玩家背包是否每步正确同步。
3. 右上角 Mixin Accessor 在实际 JEI 11.6.0.1024 Forge JAR 中的字段名/运行映射是否与源码标签一致；需通过启动测试确认。
4. AE2 处理样板手动候选/数量 wrapper，以及工作台 `SET_FILTER` 覆盖用户选项，需在实际编码终端测试。
5. 自动分发每次 `InventoryActionPacket` 后是否按预期收到 `PatternAccessTerminalPacket` 增量更新；手动兼容性已确认，自动化回执尚未实测。
6. AE2 终端 group 没有唯一机器 ID；完全相同的分组无法由客户端自动区分，只能通过玩家命名/手动选择解决。

## 7. 主要源码链接

- CTI 整合包清单：<https://www.mcmod.cn/modpack/989.html>
- Quick Craft：<https://github.com/Sxilverrr/Quick-Craft>
- Quick Craft MIT License：<https://github.com/Sxilverrr/Quick-Craft/blob/main/LICENSE>
- JEI v11.6.0：<https://github.com/mezz/JustEnoughItems/tree/v11.6.0>
- AE2 forge/v12.9.9：<https://github.com/AppliedEnergistics/Applied-Energistics-2/tree/forge/v12.9.9>
- ExtendedAE 1.19-0.2.2-forge：<https://github.com/GlodBlock/ExtendedAE/tree/1.19-0.2.2-forge>

## 阶段7补充：JEI精确返回与AE2替换（0.7.1）
本节只核对实际新增调用，依据当前本地JEI11.6.0.1024和AE2 12.9.9反混淆JAR，不重复阶段0。字节码证据见outputs/stage7-supplement。
- RecipesGui.logic类型IRecipeGuiLogic；RecipeGuiLogic.state类型IngredientLookupState，含类别索引、配方索引、焦点、FocusedRecipes和每页数量。
- RecipeGuiLogic#setState(IngredientLookupState,boolean)为private，第二参数控制是否将旧状态放入history；它调用原stateListener.onStateChange。恢复用false，不直接写字段。
- RecipesGui#open()为private；以当前Screen设置parent并打开原界面，不重新setFocus。通过最小Invoker调用，原parent先恢复。show(OUTPUT)会新建查询并选择类别首项，不能代替精确恢复。
- PatternEncodingTermMenu#isSubstitute/isSubstituteFluids返回终端同步值；原encodeCraftingPattern将两者传给PatternDetailsHelper。EncodingHelper和PatternEncodingLogic.setMode未重置两值。
- AECraftingPattern#canSubstitute/canSubstituteFluids能核对生成的实际Crafting样板。原encodeProcessingPattern只接受GenericStack输入/输出，没有替换标志，不能为Processing伪造这两项状态。
- G悬停来源仍为JEI覆盖层/书签、RecipesGui、IScreenHelper、已有FTB集成和AbstractContainerScreen.getSlotUnderMouse。玩家背包/容器槽位不要求在JEI内；必须在空手恢复历史树前读取悬停目标。

## 阶段8新增调用核对
- 依据本地AE2 12.9.9 JAR：PatternAccessTermScreen公开postFullUpdate(long,long,PatternContainerGroup,int,Int2ObjectMap)、postIncrementalUpdate(long,Int2ObjectMap)、clear()。只在原方法完成后通知预览刷新，不改数据。
- 原assets/ae2/screens/pattern_access_terminal.json：search left104/top4/width65/height12；scrollbar left175/top18。分发13×13按钮放在left175/top4，不覆盖搜索框。
- GenericEntryStackHelper.ofInputs使用IngredientConverters。独立Java夹具必须先注册AE2原ItemIngredientConverter；游戏中由AE2 JEI插件注册，不能为测试在产品中重复注册。
- ForgeGradle6.0.54使用compileOnly fg.deobf和独立runtimeOnly；包内可选Mixin在FMLLoader.getLoadingModList发现清单上门控。运行组合和Mixin实注入仍须游戏验收。

## 0.8.1 UI调用核对
- 当前MC/Forge反混淆字节码：GuiComponent.drawString及drawCenteredString调用Font.drawShadow。浅底深色文字改用Font.draw。
- Screen.renderTooltipInternal在传入PoseStack上增加400深度，GUI投影近裁剪对应z1000；z700覆盖层的Tooltip不能直接再加400。当前先回退350，最终Tooltip在750。
- 当前AE2 Scrollbar.DEFAULT原生宽12，管理终端样式x175/y18；分发按钮使用相同x和宽度，在y0留6px间隔。深度按钮使用JEI11.6 GuiIconButtonSmall及原recipeTransfer加号图标。
