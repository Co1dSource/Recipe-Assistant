# Recipe Assistant

添加JEI 配方树与一键合成，支持 AE2 批量样板编码和自动分发。

## 控制

按 G 打开配方树。

## 支持与依赖

- Minecraft 1.20.1、Forge 47.4.0 至 48 之前、Java 17
- 必需：JEI 15.20.0.112 至 16 之前
- 可选：AE2 15.4.12、ExtendedAE 1.20-1.4.22-forge、FTB Library 2001.2.13、Tinkers' Construct 3.11.2.166（Mantle 1.11.128）
- 可选模组的运行前置：AE2 需要 GuideME 20.1.13，ExtendedAE 还需要 Glodium 1.20-1.5-forge，FTB Library 需要 Architectury 9.2.14。
- 仅客户端安装。样板和合成操作使用相应模组的原生容器。

## 构建

使用 Java 17 运行 `gradlew.bat clean build`，产物位于 `build/libs`。
默认按最低支持版本编译。开发回归可用 `-Pforge_version=47.4.28 -Pjei_version=15.56.0.205`。
开发客户端默认带 AE2 和 ExtendedAE。用 `-PwithAe2=false` 启动基础配置，用 `-PwithExtendedAe=false` 单独禁用 ExtendedAE。
用 `-PwithFtbLibrary=true` 或 `-PwithTconstruct=true` 启用相应开发依赖。
