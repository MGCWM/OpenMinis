# OpenMinis-Linux 1.36.10-linux

- versionCode **63**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版摘要

人格提示词改为文件名入口 + 二级编辑页；可从手机导入 `.md` / `.txt` 到私有目录并用下拉选择；不同供应商可绑定不同提示词。

## 变更

### 人格

1. **文件名入口** — 设置页不再内嵌超长正文。显示当前文件名，点击进入二级页查看或编辑。
2. **导入** — + 号用系统文件选择器选 `.md` / `.txt`，复制进 `filesDir/minis-global/memory/personas/`，不保留源 URI。
3. **下拉选择** — 在 `SOUL.md` 与已导入文件之间切换当前提示词。
4. **按供应商** — 每个供应商可单独绑定一份提示词，或「跟随默认」。系统提示词按当前模型的 `providerInstanceId` 解析。
5. **语言 Tad 去掉** — Auto / 中文 / English 三选一已移除。
6. **自绘图标** — 文件、加号、供应商、chevron 用 Canvas 描边。

## 兼容性

- 数据库仍为 Room 16，无迁移。
- 身份（名称 / 风格 / 图标）仍写在 `SOUL.md`。导入文件只替换人格正文。
- 签名、包名不变。侧载覆盖安装即可。可与官方 OpenMinis 并排安装。已装 1.36.9（versionCode 62）会被 63 覆盖。
