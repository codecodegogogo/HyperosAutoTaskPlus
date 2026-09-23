# Hyper自动任务增强

一个 LSPosed 模块，给 HyperOS「安全服务」里的**自动任务**补了几个原生没有的功能。

- 目标版本：安全服务 12.3.5（260211.0.1），包名 `com.miui.securitycenter`
- 模块包名：`io.github.codecodegogogo.HyperosAutoTaskPlus`
- 只 hook 安全服务，没有界面，装上、在 LSPosed 里勾选、重启安全服务即可

## 增加的功能

### 1. 「启动应用 / 离开应用」条件可以搭配「打开应用」结果

原生：条件选了「启动应用」或「离开应用」后，结果列表里的「打开应用」会被置灰。
现在：不再置灰，可以做“打开 A 时自动打开 B”这类任务。

### 2. 新增两个条件：冷启动应用、杀死应用

在「添加条件 → 事件」里，紧跟在原生「启动应用 / 离开应用」之后。

- **冷启动应用**：应用进程从无到有（被加载进内存）时触发
- **杀死应用**：应用的所有进程都被清出内存时触发

和原生「启动 / 离开应用」的区别：原生看的是前后台切换，这两个看的是进程是否存在。
支持多选应用，支持「退出时恢复」（两者互为退出条件）。

### 3. 新增结果：隐身模式

在「添加结果 → 设置项」里，紧跟在「定位」之后，可选「开启」或「关闭」。

隐身模式是系统权限中心里那个开关（开启后所有应用无法录音、定位、拍照），系统只在设置页给了开关，
没有快捷入口。现在可以在自动任务里自动开关它，勾选「退出时恢复」会在任务退出时切回相反状态。

### 4. 新增条件：屏幕状态

在「添加条件 → 事件」里，紧跟在「锁屏」之后。界面和「电量」条件一样是两张卡片：

- **亮屏时间**：屏幕持续点亮了指定时长后触发
- **息屏时间**：屏幕持续熄灭了指定时长后触发

时长在输入框里填数字，单位可选秒 / 分钟 / 小时 / 天，填 0 表示一进入该状态就触发。
勾选「退出时恢复」时，退出条件是相反的状态（例如「息屏 10 分钟」的退出条件是「亮屏时」）。

### 5. 退出条件可以自定义

原生：勾选「退出时恢复」后，退出条件只能是每个触发条件的反向条件（自动生成、不能改、不能删）。
现在退出条件列表和触发条件列表一样可以操作：

- 列表末尾有「添加条件」，可以额外加任意条件作为退出条件（自定义时间除外）
- 点已有的退出条件可以修改，右侧有删除按钮，自动生成的那条也能删掉换成别的
- 有多条时每条前面有勾选框，勾选的才生效

触发条件之间是「与」（全部满足才执行），退出条件之间是「或」（勾选的任一满足就恢复），和原生一致。

## 构建

依赖已经放在仓库里，不需要 Android SDK / Gradle：

- JDK（脚本里写死在 `D:\Environment\java`，按需修改）
- `build/libs/`：`android-4.1.1.4.jar`、`xposed-api-82.jar`
- `build/tools/aapt2.exe`
- d8 用的是 jadx 自带的 jar（脚本里的 `JADX_JAR` 路径按需修改）

```powershell
.\build.ps1      # Windows PowerShell
./build.sh       # Git Bash / Linux
```

产物：`build/out/apk/HyperAutoTaskEnhance.apk`。第一次构建会自动生成调试签名 `build/debug.p12`。

## 目录

```
module/
  AndroidManifest.xml
  assets/xposed_init                 模块入口类名
  res/                               图标、名称、作用域
  src/io.github.codecodegogogo.HyperosAutoTaskPlus/
    MainHook.java                    入口，依次装载下面三个功能
    UnlockAppResultHook.java         功能 1
    FirstAppConditionHook.java       功能 2 的 hook 部分
    InvisibleModeResultHook.java     功能 3 的 hook 部分
    ScreenStateConditionHook.java    功能 4 的 hook 部分
    ExitConditionHook.java           功能 5
    FirstAppKeys.java                各条件 / 结果的 key 常量
    DexInjector.java                 把模块 dex 注入安全服务的 ClassLoader
    HookUtils.java                   按签名找方法等公共工具
    inject/                          会被注入到安全服务里运行的类
      FirstAppConditionItem.java     冷启动 / 杀死应用条件的基类
      FirstStartAppConditionItem.java
      FirstLeaveAppConditionItem.java
      FirstAppRuntime.java           进程存活跟踪，通知引擎重新判定
      InvisibleModeResultItem.java   隐身模式结果项
      InvisibleModeRuntime.java      切换隐身模式、弹开启 / 关闭选择框
      ScreenStateConditionItem.java  屏幕状态条件项
      ScreenStateRuntime.java        亮 / 灭屏计时、到点通知引擎、卡片式编辑对话框
      EngineBridge.java              把「请重新判定这些条件」交给引擎，各运行时共用
  stubs/                             安全服务与 miuix 类的编译桩，只参与编译不进 dex
build.ps1 / build.sh                 构建脚本
```

安全服务 APK 及其反编译输出、构建产物、签名密钥都在 `.gitignore` 里，不随仓库上传。
