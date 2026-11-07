# 当前交接（Recipe Assistant 0.8.2）
- 正式项目许可为MIT，作者Co1dSource；根LICENSE为标准全文，mods.toml与README一致，JAR内META-INF/LICENSE必须逐字相同。原Forge MDK许可移至licenses/FORGE-MDK-LICENSE.txt，Quick Craft原版权和MIT全文单独保留，不能覆盖。
- 第三方边界见THIRD-PARTY-NOTICES.md。禁止把依赖类、资源或JAR打进本模组。用户确认Modrinth/CurseForge未发布；发布时统一选择MIT，见docs/RELEASE_LICENSE.md，不声称已修改不存在的页面。
- 0.8.2只改许可与打包元数据，没有改功能代码。构建、MIT正文/作者/元数据/归档检查及第三方原文件哈希核对通过，证据outputs/mit-release-082，发布哈希见docs/mit-release/ARTIFACT.json。
- 对外名称、资源描述、按键分类和发布JAR已改Recipe Assistant / recipeassistant-0.8.2.jar，不绑定整合包；内部modid和偏好文件前缀保留，兼容既有设置。
- 用户确认阶段0～7验收，明确本轮由Codex直接实现并上传Git；不调用Qoder、不派子代理。阶段8记录在docs/stage8，执行基线和原始日志在outputs/stage8。
- 核心只引用Minecraft/Forge/JEI类型。OptionalIntegrations在客户端setup后检测ae2才创建Ae2Integration，手动注册编码/分发事件；禁止给这些类重新加自动EventBusSubscriber。
- AE2类型、GUI和Accessor都在integration/ae2。Mixin插件在早期使用Forge已发现的mod清单门控AE2目标，不能改成客户端setup才决定是否注入。
- ExtendedAE无需专属类，复用AE2同步的PatternContainerRecord。只有AE2初始化且检测到expatternprovider时才接纳36槽；9槽不依赖ExtendedAE。
- 分发预览必须保留在原终端覆盖层。只读注入postFullUpdate/postIncrementalUpdate/clear尾部标记数据变化，下一次绘制/点击刷新；不定时扫描供应器。玩家菜单stateId变化时更新背包样板列表。
- 分发按钮与AE2原滑块同列：x175/y0、12×12，滑块x175/y18、宽12，间隔6px。坐标相对getGuiLeft/getGuiTop。覆盖层z700，Tooltip调用前回退350，原生再加400后在z750，不能直接叠到1100。
- 编码预览从Entry.inputs及Entry.slots取得与编码相同的数据，保留工作台9槽、手动输入数量、唯一目标输出及终端替换标记。处理材料合并后的数量不能再从树文字推算。
- 编码预览只复用匹配的手动树，否则创建临时树；不得写RecipeInteractions.last。G恢复历史只由openItem/openLayout主动打开树写入。
- 深度框新增原生JEI小型−/+控件，无Tooltip，0和int上限分别禁用，改值走原EditBox responder保存并重建预览。
- 浅色面板上的深色标签/树按钮使用font.draw，不能用drawString/drawCenteredString，它们实际调用drawShadow。
- 本次修复与审查见docs/ui-fix-081，G历史问题有先失败再通过的回归记录；构建与定向检查通过，未运行实际游戏视觉验收。README和GitHub仓库描述只介绍功能/操作/版本及依赖联动。
- G仍先处理PatternConfirmation当前问题，之后才检查EditBox焦点；精确JEI状态恢复及原+确认流程保留。长列表滚轮范围与绘制边界一致。
- 核心库存ItemIdentity保留物品、真实NBT和ForgeCaps快照，不能仅按注册名或显示名统计AE2现货。
- 本轮未运行Minecraft图形客户端或CTI专用服务器。构建/无游戏定向检查与真正安装组合测试分开记录；不要将下方历史待验收状态覆盖用户本次对0～7的确认。

## 阶段7补充修复断点（0.7.1）
- Codex直接实现；原阶段7分发保留，不调用Qoder、不进入阶段8。用户追加：背包、容器物品也支持鼠标悬停G，空手不能提前恢复旧树。
- 外部供应只表示本树不生产。未选路线、无JEI路线及主动删除统一正常显示，不产缺路线问题，不阻止编码；合成缺料只报实际缺量。未加载节点也显示外部供应。真循环/真实安全上限仍保留。
- 材料来源不绑定ME。阶段5已有玩家/九宫格、AE2现货和匠魂原处理器材料槽保持原样。没有新增背包内部库存适配/依赖或容器扫描；未被这些接口暴露的背包内部物品不能宣称支持。悬停打开背包UI物品不等于已支持从背包内部自动取料。
- 问题界面G在数字框/JEI焦点过滤前处理，立即使用当前问题的准确node；真循环/不支持诊断也能查看并换路线。无普通未选路线问题。
- RecipesGui原生IngredientLookupState保存类别、recipeIndex、focus和FocusedRecipes，连同原parent及目标输出记忆保存。恢复走JEI原生setState和open，不重新查询输出。新增RecipeGuiLogic只读state Accessor+setState Invoker；原RecipesGui增加logic getter/open Invoker，没有私有字段直接写入。窗口尺寸变化时JEI可能重新排布，未承诺逐像素位置或候选轮播相位。
- 编码启动保存终端isSubstitute/isSubstituteFluids，删除强制关闭；JEI原helper和模式切换不重置两值。填料同步后核对终端仍为保存值，生成的Crafting样板原生解码必须匹配两值。
- AE2 12.9.9原生Processing编码只存输入/输出，没有两项替换标志。保留终端设置，按原生适用范围处理，不伪造标志、不改样板NBT。
- 主预览及数量页删除上一页/下一页按钮，长列表滚轮翻动；仅多问题显示有效的上一项/下一项。树详情删除翻页点击热区，只有多页时显示滚轮提示。
- 构建build.log成功，用户追加悬停修复后build-hover.log成功。四组最终定向检查通过，追加HoverCheck先复现空手错误再通过。JAR为build/libs/ctirecipeassistant-0.7.1.jar；记录见outputs/stage7-supplement。
- 实际GUI、Mixin注入、CTI连续编码/替换、背包兼容及联机操作未实测，按GAME_TESTS.md验收。以下0.7.0及阶段6记录为历史，外部供应/G限制以本节为准。

## 阶段 7 历史断点（0.7.0）
- 用户确认阶段0～6已验收，并明确由Codex直接实现，不使用Qoder，不派子代理。原有未提交成果按基线保留。停于阶段7，等待游戏验收，不开始阶段8。
- 深度只在批量编码预览输入，保存requestedDepth（非负int），effectiveDepth=min(requestedDepth,actualTreeDepth)。树右上深度文字与热区移除；输入立即重建列表。
- 右键节点详情提供「删除此生产配方」。只清当前节点生产关系和子树，材料保留为外部供应，不写全局配方偏好。JEI重选恢复；对应一键合成视图保留当前树截断，不影响独立树。
- 同一工作台批次的同目标、同生产路线输入先合并需求再扣库存。只有当前路径的相同材料+生产配方+类别重复才报循环。相同材料走不同生产配方可继续；外部供应先于循环分类，64层安全上限独立保留。
- 问题按类型、材料含NBT、生产配方/候选类别及目标去重，累计涉及数量；保留代表节点和其他路径标记。循环另保留导致该依赖的配方类别。G/分页使用合并后记录，编码不计算订单数量。
- 分发用PatternAccessTermScreen.byId最小只读Accessor，读取PatternContainerRecord实时库存及group。实际槽数9为普通、36为扩展；分子装配室按group.icon的ae2:molecular_assembler识别。其它机器按同步名称/tooltip精确匹配，不能确定就手选。
- group不提供稳定坐标。持久化仅group(icon完整内容/name/tooltip)摘要和配方类别+类型，世界隔离。serverId只留本次预览内存；同组多个重开终端后重选。可以覆盖原分组映射，多类别相同内容先选实际类别。
- 预览为原终端覆盖层，不能换普通Screen：AE2供应器更新包只交给当前原生终端。库存变化后点刷新，开始及每张操作前读实际快照。
- 正常玩家PICKUP取样板并等源槽/光标回执，再用既有InventoryActionPacket。单张PICKUP_OR_SET_DOWN，叠放用原生右键SPLIT_OR_PLACE_SINGLE只放一张，余量正常回包并等同步。目标槽完整内容及光标减少后才记成功。
- 不增自定义包，不写实体样板NBT，不直接写远程库存/光标。取消等当前回执后收尾；失效/超时停止且不重发。超时光标物品提示手动收回。目标满保留并跳过，腾空后刷新；背包不能放回则暂停。
- 同内容多类别的既有样板需在预览确认重复类别；执行中新出现歧义停止，不自动跨类别跳过。
- 最终构建build-release.log成功，三组Java定向检查通过；JAR为build/libs/ctirecipeassistant-0.7.0.jar。
- 证据在outputs/stage7。游戏内GUI/Mixin、普通/扩展真实上传、联机同步及取消未由Codex实测，按GAME_TESTS.md验收。

# 项目交接

## 阶段 6 修正当前断点（0.6.1）
- 用户提供完整阶段 6 修正规格，确认阶段 0～5 已验收，授权 Codex 直接实现；没有 Qoder、子代理、提交或发布。本轮基线在 `outputs/stage6-interaction-fix/baseline-src/`，后续按本目录 REVIEW 和 GAME_TESTS 验收，不开始阶段 7。
- `RecipeInteractions` 只协调当前世界会话的原树实例、准确待选节点、返回界面和编码确认。JEI 配方上 G 使用实际布局绑定根配方；空手 G 恢复同一树对象，展开、子层、平移、缩放和深度不丢。切换选配方不关闭玩家当前容器。
- 原 + 的区域、图标和大小不变。待选时所有有效 JEI 类别均可显示本按钮，Tooltip 为“选择此生产配方”；只绑定当前节点，不能转入合成或编码。目标不匹配禁用。G 不确认；ESC 从 JEI 选择返回原树或问题列表，保持上下文。
- JEI 11.6 没有公开的当前 OUTPUT 焦点 getter。新增的 RecipesGuiFocusMixin 仍只作用于已有 RecipesGui 目标，在公开 show(List) 和 showTypes(List) 入口只读记录/清除查询焦点。原两个只读 Accessor 不变；不反射猜接口或写 JEI 字段。多输出无法确定目标时要求悬停产物后 G，不回退为第一个输出。
- 树已删除左上返回和自定义生产配方面板，保留材料变体/产物详情。中键进入子树；ESC 每次退一层，恢复对应视图。重复选择相同配方保留现有子节点和展开状态。根关闭时通过 JEI 公共 show 恢复查询及有效父菜单，不重新打开已失效容器。
- `PatternPlanner.plan(root, depth)` 用当前原树节点，不重建树，根为 0。超过用户截止直接结束，不报安全限制；按依赖后序及配方/目标去重。默认深度 1，最大值来自原树当前实际路径；内部 64 层/4096 节点保护独立。树/预览共用深度，按最终目标保存。
- 问题为 `Issue(node,type,message,path)`，按节点身份保留，不用同名材料搜索替代。每页一项；G 与当前页使用同一个 currentIssue。仅 CHOICE 能进入 JEI，CYCLE/SAFETY/UNSUPPORTED 保持独立提示。选择回原分页后立即重检并移除已解决项。
- Forge 原生 ScreenEvent.Closing 负责在所有 JEI 离开方式下清理待选节点，Opening 在重新打开树时废弃旧编码确认；不会改写原事件。已按实际 Minecraft.setScreen 字节码确认事件先后及同一 JEI 实例导航不触发 Closing，避免把原生分页当退出。
- 编码预览复用原节点、选择和已有 PatternEncoder.Entry；删除额外确认按钮。ESC 返回终端来源的 JEI，点击同配方原 + 确认，再调用已有编码执行器。换另一张配方只打开新预览，不能误启动旧队列。手动数量、多目标输出及 PatternRecordStore 保留；执行器 Tick 至末尾的真实物品操作代码未改。
- 缺料根因是工作台模式自动采用会拆回祖先材料的唯一反向转换。本轮只对未手选的此类反向路线当外部供料；不改用户显式路线。8 铁锭+8 铁粒合铁块应报告缺 1 铁粒，9 铁粒可补足；显式选择反向闭环仍报真实循环。循环与内部安全上限不再共用一句错误。
- 本轮构建成功，真实 GUI 交互、Mixin 注入与联机编码/同步尚未运行游戏测试。保留现有纯客户端协议、空白不足/满包暂停和取消流程。历史记录如下。

## 阶段 6 的 0.6.0 历史断点
- 本次用户明确要求 Codex、不使用 Qoder，并确认阶段 0～5 已通过验收。仅新增阶段 6，未提交、发布或启动阶段 7。
- `PatternPlanner` 复用 RecipeNode 和全部类别 JeiRecipeIndex。绑定 JEI 点击的根配方，不写根选择偏好；按依赖后序排列，以配方指纹及目标去重。未选择的生产路线、循环或 64 层边界阻止整批开始。不调用 CraftPlanner，不计算订单材料量。
- `PatternPreviewScreen` 提供目标主/副产物切换、配方/机器/问题预览及处理输入数量编辑。手动数量按配方、目标及输入槽保存；不改输出量，不自动推算概率。全部实际输入须能被 AE2 转换；工作台需是九槽且具体选材能产出目标。
- `PatternEncodingSlots` 保留每个输入位置，仅给出选定具体材料及数量，只暴露目标输出。通过当前菜单已注册的 EncodePatternTransferHandler 填入。普通 CraftingRecipe 走工作台模式；其它类别使用处理模式，包括本阶段按机器工序处理的切石/锻造类别。
- AE2 12.9.9 的 setMode/setSubstitute/setSubstituteFluids 客户端只发原 action，不本地预测字段。工作台在 handler 后用原 SET_FILTER 固定九槽并关闭原料/流体替代。执行器等待真实槽位、模式及目标吻合后另调 menu.encode()；不调用 PatternDetailsHelper 的生成方法，只解码核对服务端样板。
- 空白先用原空白槽，不足时通过原 QUICK_MOVE 从玩家背包补入；没有空白则暂停，可手动使用原终端来源补充。确认编码消耗一张且输出出现后才取出；普通 PICKUP 和放回都不做客户端预测，必须观察实际回执。内容或会话不符、100 tick 超时即停止，不在回执未知时重编。
- 背包满时样板留在输出槽。只允许操作玩家背包清理空间，阻止原编码按钮覆盖待取样板。点继续后取出并确认入包。取消收尾当前操作；满包时可取消并保留输出槽样板。关闭终端/断网停止，不自动重开。
- `PatternRecordStore` 在服务端样板完成且解码核对后保存本地映射；成功入包后才推进队列完成数。复用按服务器/世界隔离的 RecipePreferences 文件，键 pattern-record|category|fingerprint；原样板内容规范化排序后取指纹，类别参与记录身份。记录目标、输入及数量、机器和 Crafting/Processing 类型，不写实体样板 NBT。
- `outputs/stage6/` 有基线源码、Git 状态和差异、实际 AE2 源码、三组最终检查、构建和产物证据。基线比对确认原源文件仅分发器被本轮修改，其余阶段 2～5 源码原样保留。新增五个源文件，版本 0.6.0。
- 构建首次宿主自动审批超时，命令未启动；重试后实际 build 成功，含 reobfJar。`test` 为 NO-SOURCE；不将其称为单元测试通过。游戏内真实编码、联机同步、暂停及取消均待 `outputs/stage6/GAME_TESTS.md` 验收。产物 `build/libs/ctirecipeassistant-0.6.0.jar`。

## 阶段 5 修正当前断点（0.5.1）
- 用户修正：一键合成只匹配工作台候选，并要求接入 AE2 合成终端及匠魂工作站。最新构建为 0.5.1，记录在 `outputs/stage5-crafting-terminal/`；未开始阶段 6。
- JeiRecipeIndex 增加仅工作台范围，执行器使用该范围。RecipeNode 在该范围内回读已记住的工作台路线，不让机器偏好阻塞或混入执行。普通树的全类别查询不变；不改写用户已有机器偏好。
- CraftingInventory 绑定真实菜单槽位。原版结果槽 0；AE2 按 SlotSemantics 取九宫格、结果、背包和快捷栏，排除无线终端锁定槽；匠魂直接调用 CraftingStationTransferInfo 取配方/材料槽，结果槽为 9，接收产物仍只用玩家背包。没有新增附近容器扫描。
- AE2 数量直接读取已有 ClientRepo.getAllEntries 的 storedAmount，不计算 craftable/requestable 数量。网络快照每 Tick 至多取一次；库存或偏好变化时失效计划缓存。数量规划按所需物品读取真实来源，不把玩家背包冒充网络库存。
- AE2 12.9.9 的 UseCraftingRecipeTransfer 忽略 slotsView 的选材，且读取 Ctrl。AeCraftingSelection 只生成本次 JEI 转移的瞬态选材元数据，不注册配方。AE2 自己检测到该 ID 不存在后发送既有模板填料包（recipeId=null、craftMissing=false）；实际结果只由服务器已有真实配方生成。客户端严格核对九槽选材和结果；NBT 选材若未被服务端满足则停止，不能悄悄替换后取结果。Ctrl 在预检查及每次调用前阻断，不调用 CPU。
- AE2 结果走其原界面的 InventoryActionPacket(CRAFT_ITEM)，一次普通批次；光标出现预期产物是未预测的服务端回执。AE2 可能自动补回九宫格，所以不再要求取结果后网格必须变化。原版/匠魂仍用正常 PICKUP，放回背包继续逐槽等待同步。
- 本地实际依赖为 TConstruct 3.8.5.58、Mantle 1.10.48，从用户 CTI 客户端只读复制到 reference/mods，作为可选 compileOnly；不改原模组、不嵌入依赖、不增加服务端要求。匠魂适配类有 ModList 加载边界。
- 新游戏步骤覆盖仅 ME 现货、无线锁定槽、AE2 补料、匠魂连接容器、取消与原版回归。尚未执行游戏内验收，不将构建或模拟菜单检查当作联机通过。

## 阶段 5 的 0.5.0 历史断点
- 本次用户授权 Codex 直接实现，不调用 Qoder；用户确认阶段 0～4 已验收。新版本为 0.5.0，未开始阶段 6。
- CraftPlanner 复用 RecipeNode、JeiRecipeIndex 和 RecipePreferences，最终根配方绑定点击布局，不写回根偏好。共享数量账本按依赖顺序消耗已有材料，补足整批并保留余量；最终只执行一次。已知物品容器返还可供后续步骤使用。
- SelectedRecipeSlots 只收窄输入候选，保留空槽顺序；JEI 仍负责实际配方填入。源 JEI 布局不修改。索引仅额外保留候选对应的真实布局。
- CraftExecutor 只执行原版 CraftingMenu 及 JEI 标准 BasicRecipeTransferHandler。其他终端因没有与处理器一致的整树库存来源而明确灰显；没有读取 ME 网络或附近容器。空间模拟只修改副本。
- 每步 false/false 检查、false/true 填入，等待 stateId 与工作台配方同步。结果只发原版 ServerboundContainerClickPacket/PICKUP，使用当前真实 carried 和空预测差异，不执行本地 clicked；服务端按正常菜单执行并回传真实槽位/光标。这样不会把客户端预测当回执。只改本客户端代码，不复制服务端逻辑。
- 一次取一个结果批次。确认光标产物与输入消耗后，每次只放入一个背包槽，确认实际移动数量后继续。100 tick 只作失败超时，没有固定等待延迟。取消在当前回执后收尾光标；超时、断线或换菜单直接停止并保留实际状态。
- 运行时锁住物品操作，保留 ESC 关闭，顶部可点击取消或按退格。预检查按 JEI 布局分别缓存，库存、偏好或菜单变化时失效，避免多张配方每帧重建树。
- 定向检查与构建记录见 `outputs/stage5/`。游戏测试未执行；尤其需要确认 CTI 服务端接受无本地预测的正常点击、逐步同步及取消。原阶段 4 SingleRecipeTransfer 保留，只增加 busy 查询；新增按钮现用于整树，原 JEI 下方按钮仍是单步填入。
- 开始时已有未提交修改，基线保存在 `outputs/stage5/baseline.diff`、`baseline-status.txt`、`baseline-head.txt`。未修改原按钮布局/可见性、阶段 3 模式、Mixin、依赖或模组 JAR，没有提交或发布。

## 阶段 4 历史断点
- 最新修复版 `build/libs/ctirecipeassistant-0.4.1.jar`。构建及结构/Tooltip 定向检查通过，记录在 `outputs/stage4-visibility-fix/`；九项游戏测试尚未执行，阶段 5 未开始。
- 模式增加 HIDDEN。JEI 父菜单必须仍为玩家当前菜单。管理终端、背包 2×2、箱子、熔炉和无父菜单隐藏；有效原版工作台显示。其他终端读取真实九个合成输入槽、真实结果槽，且当前配方有 JEI 转移处理器才显示。绘制与点击逐配方实时解析，隐藏时无点击拦截。
- 缺料 Tooltip 仅在新增按钮 showError 期间通过 Forge GatherComponents 替换首行“转移物品”为本按钮标题，JEI 原错误和高亮保留；finally 清除作用标记，原按钮 Tooltip 不变。
- 本次只修可见性和文案，其他终端实际单步填入仍受阶段 4 原版 CraftingMenu 限制并明确灰显原因。下方 0.4.0 的所有禁止场景灰显规则已被本次 HIDDEN 规则覆盖。
- 用户确认阶段 0～3 已验收，本轮明确由 Codex 直接实现，不使用 Qoder。最新产物 `build/libs/ctirecipeassistant-0.4.0.jar`；构建 `outputs/stage4/build-02.log` 成功，含 reobfJar。阶段 4 游戏同步尚待验收，不开始阶段 5。
- SingleRecipeTransfer 只允许原版 CraftingMenu、JEI CRAFTING 类别及对应处理器；先 false/false 检查，再 false/true 单次填入，不操作结果槽。材料和背包空间检查完全交给 JEI。
- IRecipeTransferError 没有公开文字 getter，直接调用 showError 绘制原提示及缺料高亮；编码模式沿用原只分发入口。
- 同步通过输入槽 1～9 快照、服务端 stateId 变化、CraftingMenu.recipeMatches 和两 tick 稳定确认；超时 100 tick、菜单或 JEI 失效则停止。已匹配配方只提示原状态，不声称本次填入成功。
- 本轮计划、初始差异、API 字节码、产物核对、审查及游戏步骤在 `outputs/stage4/`。原有阶段 3 未提交修改已保留。未改配方树、Mixin、依赖版本或服务端。

## 阶段 3 历史断点
- 0.3.1 按用户截图修正新增按钮偏左 2 个 GUI 像素的问题，直接取原填入按钮横坐标。普通工作台 CraftingMenu 和 AE2 合成终端 CraftingTermMenu 仅在 JEI RecipeTypes.CRAFTING 类别显示；烧炼等类别、背包/箱子/普通存储/管理终端均隐藏，隐藏位置不拦截点击。样板编码终端仍保留各类配方入口。绘制和点击使用相同过滤条件。
- 最新产物为 `build/libs/ctirecipeassistant-0.3.1.jar`。`outputs/stage3-button-fix/build-03.log` 构建成功，`check.log` 定向检查通过。用户截图已证明 0.3.0 新按钮可以加载显示；0.3.1 的对齐、合成终端及类别显示条件仍待游戏复测。下方 0.3.0 为历史记录。
- 用户已确认阶段 1/2 游戏验收通过，并要求 Codex 直接执行阶段 3。最新产物 `build/libs/ctirecipeassistant-0.3.0.jar`，最终构建日志 `outputs/stage3/build-03.log` 成功。停在阶段 3，等待游戏验收；不开始阶段 4。
- JEI 11.6 两字段只读 Accessor 使用 remap=false，Mixin 配置只有 client 项，通过 JAR Manifest MixinConfigs 加载。实际 CTI 中的 Mixin 应用待验证。
- 新按钮复用 JEI GuiIconButtonSmall 和原生纹理，以 GUI 坐标绘制和命中。Rect2i.contains 包含右下边界，故使用与原生 Button 一致的独立命中函数；短配方向上避让原填入按钮。
- 统一入口 RecipeOperationDispatcher 当前只保存一个 pending 上下文，打印未执行提示。模式按 JEI parentScreen 的菜单判断，只有 PatternEncodingTermMenu 为 BATCH_PATTERN_ENCODING。PatternAccessTermMenu 为 ONE_KEY_CRAFT。
- 本地 AE2 12.9.9 的管理终端类实际为 `appeng.menu.implementations.PatternAccessTermMenu`，不是 `appeng.menu.me.items` 包；本阶段不导入管理终端类，只检查真实编码菜单类型。
- 操作上下文保存准确 recipe/category/slotsView/rect、阶段 2 RecipeCandidate 及首个非空输出槽当前显示目标。全部输出保留；没有材料检查、transfer、encode、物品交互或网络调用。后续通过分发入口和 buttonState 接入实际执行。
- 无游戏检查与产物证据在 `outputs/stage3/`，真实模式、缩放及点击按 `GAME_TESTS.md` 验收。以下“当前范围”为阶段 2 历史记录。

## 当前范围
- 最新版为 `build/libs/ctirecipeassistant-0.2.4.jar`，包含 FTB 任务控件悬停和森林法杖模式修正。构建日志 `outputs/stage2-ftb-hover/build.log` 成功（含 reobfJar）；44690 字节，SHA-256 `CD9A8A4CC5298B8FD8AE718E6EECED0CE1F72234D63C9147801735AD7FC0D3E6`。两项游戏表现待复测。
- 用户实际模组目录为 `A:\PCL\.minecraft\versions\Construct Technological Innovation\mods`。仅只读检查；FTB Library 1902.4.1-build.236 已复制到项目 reference/mods，作为可选 compileOnly 依赖。FTB Quests 1902.5.9-build.399、XMod Compat 1.2.4、Botania 1.19.2-440 已从实际 JAR 核对相关接口。
- FTB 任务按钮不是容器槽：先补充 JEI IScreenHelper 通用悬停，再按可选 FTB IScreenWrapper→getGui→getIngredientUnderMouse→WrappedIngredient.unwrap 读取。鼠标坐标转换为 GUI 坐标；有上下文菜单时不读取底层控件。FTB TextBox 输入保护保留。
- 物品身份仅对 botania:twig_wand / botania:dreamwood_wand 忽略 bindMode、boundTileX/Y/Z、color1/2。这些为实际 Botania JAR 核验的状态/颜色字段，仅处理拷贝。其他字段及其他物品 NBT 仍严格保留；不按字段名字猜未知模组语义。检查记录 `outputs/stage2-item-modes/` 已验证模式合并、自定义 NBT 区分、源数据不变。

## 0.2.3 历史记录
- 最新修正版为 `build/libs/ctirecipeassistant-0.2.3.jar`。生产配方卡片改为类别机器图标，缺失时取类别催化剂；树渲染后、工具栏和面板渲染前清理深度缓冲，解决物品图标深度穿透。这两项视觉调整等待游戏复测。
- 本轮构建日志 `outputs/stage2-layer-fix/build.log` 成功（含 reobfJar）；新 JAR 为 41293 字节，SHA-256 `9D82B03F68A94115F88AF0605D2A7A717E7B2990CE2BA1EFE8B09DFFAA067F70`。机器图标改动基线和构建记录在 `outputs/stage2-machine-icons/`，层级修正在 `outputs/stage2-layer-fix/`。
- 用户已澄清：仅空手无目标时无法打开，其他情况可打开，无需继续排查“完全无法打开”。0.2.1 已修正按键文案并增加三种失败原因提示及 JEI 就绪日志。
- 阶段 2 已由 Codex 按本轮明确授权直接实现，未调用 Qoder。最终构建日志 `outputs/stage2/build-04.log` 成功，停在阶段 2 等待游戏验收。
- 阶段 1 的游戏加载和多人连接已由用户确认通过。下面阶段 1 的“未测试”描述保留为当时执行记录。
- 当前 Forge 为 43.4.12；JEI/AE2/ExtendedAE 版本和客户端初始化不变。新实现与测试见 `outputs/stage2/PLAN.md`、`GAME_TESTS.md`。
- 树通过 JEI OUTPUT focus 查询，空 focus 建立布局以保留全部替代项；每个树会话缓存查询结果，不在渲染帧查询。叶子按点击展开，祖先身份阻断循环，最大深度 64。
- 持久化身份含 ingredient 类型、JEI Recipe UID 和物品/流体 NBT。配方指纹含类别、注册 ID（若有）及全部输入/输出/催化剂；不使用对象地址。
- 本地偏好按服务器地址或单人世界绝对路径的摘要隔离；分别保存目标选择、每台机器的配方和每个具体配方的槽位变体。多路线先选择，不自动执行任何工序。
- Quick Craft 主手/JEI/容器悬停取目标顺序已核对；界面使用 PoseStack，未复制新版 GuiGraphics 或服务端逻辑。第三方声明及 MIT 许可包含在 JAR 的 META-INF。
- 无游戏检查通过：71 节点布局、折叠、循环与 64 层边界。GUI 渲染、实际 CTI 配方覆盖及本轮多人连接仍未测试。其他自定义 JEI ingredient 没有通用数量 API，数量以 `(?)` 标明。

## 阶段 1 历史记录
- 2026-11-05：用户明确要求由 Codex 直接制作阶段 1，不交给 Qoder。本轮未调用 Qoder。
- 阶段 1 已实现并构建。阶段 2 及以后未开始。等待用户确认。
- 现有目录不是 Git 仓库。原始文件及本地依赖的执行前清单和 SHA-256 在 `outputs/stage1/baseline.json`。

## 工程约定
- Minecraft 1.19.2、Forge 43.4.2、Java 17。wrapper 来自官方对应 MDK，Gradle 8.8；ForgeGradle 固定 6.0.54。
- JEI 11.6.0.1024、AE2 12.9.9、ExtendedAE 1.19-0.2.2-forge 均从 `reference/mods` 的实际 JAR 反混淆。不要将 `local:` 标识当成远端 Maven 坐标。
- ExtendedAE 的实际 Mod ID 为 `expatternprovider`，不是 `extendedae`。
- `CtiRecipeAssistant` 只定义模组身份及日志。客户端初始化、按键注册和 tick 消费位于两个 `Dist.CLIENT` 事件订阅类中。默认 G，当前只输出日志。
- `mods.toml` 使用 Forge 的 `IGNORE_SERVER_VERSION`，三个模组依赖仅在 CLIENT 侧强制。没有自定义网络、服务端类或内容注册。

## 本机构建
- JDK 17 位于 `D:\Java\Java17`。不要直接依赖 PATH 中的 Oracle Java 快捷入口；该入口在本轮查询中未正常返回版本。
- 本轮构建缓存位于项目的 `outputs/gradle-home`，未改用户全局 Gradle 配置。
- `services.gradle.org` 重定向到 GitHub 的直连被拒绝。使用本机原有代理 `127.0.0.1:7897` 后成功；代理只写入本轮独立 Gradle 缓存的 `gradle.properties`。
- 复用本轮缓存：

```powershell
$env:JAVA_HOME = 'D:\Java\Java17'
$env:GRADLE_USER_HOME = Join-Path $PWD 'outputs\gradle-home'
.\gradlew.bat build --console=plain
```

## 验收证据
- 最终构建日志：`outputs/stage1/build-02.log`，`BUILD SUCCESSFUL in 4m 49s`，退出码 0，7 个任务执行，含 `reobfJar`。
- 产物：`build/libs/ctirecipeassistant-0.1.0.jar`，5992 字节。
- SHA-256：`40d392b0875a063e80f93ac4bee782102560ed762d936ddfd48957f1dbaad396`。
- `outputs/stage1/artifact-verification.json` 记录 JAR 内容、Mod ID、Java 17 字节码检查；`client-bytecode.txt` 确认 G 键值 71、客户端事件注解和重混淆方法调用。
- JAR 只含 3 个本项目 class、模组元数据及语言资源。未嵌入第三方模组，无专用服务端类引用，无阶段 2 功能。
- Gradle 的 `test` 为 NO-SOURCE，没有单元测试用例；不报告单元测试通过。当前最小工程以真实编译及产物核查验证。
- 游戏启动、G 键触发、重新绑定、开发环境 `runClient` 和 CTI 服务器连接均未测试。详细人工步骤见 README.md。
