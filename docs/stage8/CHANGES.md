# 阶段8修改文件

对比outputs/stage8/baseline，不把阶段4～7原有未提交实现算作本轮新功能。迁移前路径的内容已保留在integration/ae2。

| 类型 | 文件 |
| --- | --- |
| 修改 | `.gitignore` |
| 修改 | `API_CHECK.md` |
| 修改 | `DEVELOPMENT_STATUS.md` |
| 修改 | `HANDOFF.md` |
| 修改 | `PROJECT_SPEC.md` |
| 修改 | `README.md` |
| 修改 | `build.gradle` |
| 修改 | `gradle.properties` |
| 修改 | `src/main/java/com/cti/ctirecipeassistant/client/ClientKeyEvents.java` |
| 修改 | `src/main/java/com/cti/ctirecipeassistant/client/ClientModEvents.java` |
| 修改 | `src/main/java/com/cti/ctirecipeassistant/client/gui/RecipeTreeScreen.java` |
| 修改 | `src/main/java/com/cti/ctirecipeassistant/client/jei/RecipeInteractions.java` |
| 修改 | `src/main/java/com/cti/ctirecipeassistant/client/jei/TreeJeiPlugin.java` |
| 修改 | `src/main/java/com/cti/ctirecipeassistant/client/operation/ClientRecipeOperation.java` |
| 修改 | `src/main/java/com/cti/ctirecipeassistant/client/operation/CraftExecutor.java` |
| 修改 | `src/main/java/com/cti/ctirecipeassistant/client/operation/CraftingInventory.java` |
| 修改 | `src/main/java/com/cti/ctirecipeassistant/client/operation/RecipeOperationDispatcher.java` |
| 修改 | `src/main/java/com/cti/ctirecipeassistant/recipe/RecipeNode.java` |
| 修改 | `src/main/resources/META-INF/mods.toml` |
| 修改 | `src/main/resources/assets/ctirecipeassistant/lang/en_us.json` |
| 修改 | `src/main/resources/assets/ctirecipeassistant/lang/zh_cn.json` |
| 修改 | `src/main/resources/ctirecipeassistant.mixins.json` |
| 修改 | `src/main/resources/pack.mcmeta` |
| 新增 | `src/main/java/com/cti/ctirecipeassistant/client/gui/CompactUi.java` |
| 新增 | `src/main/java/com/cti/ctirecipeassistant/client/operation/ItemIdentity.java` |
| 新增 | `src/main/java/com/cti/ctirecipeassistant/integration/OptionalIntegrations.java` |
| 新增 | `src/main/java/com/cti/ctirecipeassistant/integration/PatternConfirmation.java` |
| 新增 | `src/main/java/com/cti/ctirecipeassistant/integration/ae2/Ae2Integration.java` |
| 新增 | `src/main/java/com/cti/ctirecipeassistant/integration/ae2/AeCraftingSelection.java` |
| 新增 | `src/main/java/com/cti/ctirecipeassistant/integration/ae2/DistributionPreview.java` |
| 新增 | `src/main/java/com/cti/ctirecipeassistant/integration/ae2/PatternDistribution.java` |
| 新增 | `src/main/java/com/cti/ctirecipeassistant/integration/ae2/PatternDistributor.java` |
| 新增 | `src/main/java/com/cti/ctirecipeassistant/integration/ae2/PatternEncoder.java` |
| 新增 | `src/main/java/com/cti/ctirecipeassistant/integration/ae2/PatternEncodingSlots.java` |
| 新增 | `src/main/java/com/cti/ctirecipeassistant/integration/ae2/PatternPreviewScreen.java` |
| 新增 | `src/main/java/com/cti/ctirecipeassistant/integration/ae2/PatternRecordStore.java` |
| 新增 | `src/main/java/com/cti/ctirecipeassistant/integration/ae2/mixin/PatternAccessTermAccessor.java` |
| 新增 | `src/main/java/com/cti/ctirecipeassistant/integration/ae2/mixin/PatternAccessUpdatesMixin.java` |
| 新增 | `src/main/java/com/cti/ctirecipeassistant/mixin/OptionalMixinPlugin.java` |
| 新增 | `src/main/resources/ctirecipeassistant.ae2.mixins.json` |
| 迁移前路径 | `src/main/java/com/cti/ctirecipeassistant/client/gui/DistributionPreview.java` |
| 迁移前路径 | `src/main/java/com/cti/ctirecipeassistant/client/gui/PatternPreviewScreen.java` |
| 迁移前路径 | `src/main/java/com/cti/ctirecipeassistant/client/operation/AeCraftingSelection.java` |
| 迁移前路径 | `src/main/java/com/cti/ctirecipeassistant/client/operation/PatternDistribution.java` |
| 迁移前路径 | `src/main/java/com/cti/ctirecipeassistant/client/operation/PatternDistributor.java` |
| 迁移前路径 | `src/main/java/com/cti/ctirecipeassistant/client/operation/PatternEncoder.java` |
| 迁移前路径 | `src/main/java/com/cti/ctirecipeassistant/client/operation/PatternEncodingSlots.java` |
| 迁移前路径 | `src/main/java/com/cti/ctirecipeassistant/client/operation/PatternRecordStore.java` |
| 迁移前路径 | `src/main/java/com/cti/ctirecipeassistant/mixin/PatternAccessTermAccessor.java` |

另新增本目录PLAN、REPORT、REVIEW、GAME_TESTS、ARTIFACT及checks中的定向检查源码。Git提交还包含执行前已存在的阶段4～7成果。

发布文件：releases/recipeassistant-0.8.0.jar，已与build/libs产物核对SHA-256相同。
