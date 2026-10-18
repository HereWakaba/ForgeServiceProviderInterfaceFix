# ForgeServiceProviderinterfaceFix

可可你啥时候学会自清理。manifest的优先级太高了。能不能拜师。

> 让“实现了 ForgeSPI 的 jar”重新被当作普通 Mod 加载 —— 在 ModLauncher 时代恢复
> `FMLCorePluginContainsFMLMod: true` 的语义。

修复了 **Minecraft 1.20.1 / Forge 47.4.16+**（实测版本 47.4.20，JDK 17）。

---

## 问题背景

Forge 1.13 将启动包装层从 `LaunchWrapper` 换成了 **ModLauncher**，随之移除了
`FMLCorePluginContainsFMLMod: true` 这个机制。

老版本（1.12.2）里，一个 jar 可以同时是 CoreMod 和普通 Mod：

```java
// net.minecraftforge.fml.relauncher.CoreModManager
classLoader.addURL(coreMod.toURI().toURL());
如果 (!mfAttributes.containsKey(COREMODCONTAINSFMLMOD)) {
    // 未标记 → 仅作为核心模组，"将不再被检查"
    ignoredModFiles.add(coreMod.getName());
} else {
    // 有标记 → 重新放回 mod 候选池
    candidateModFiles.add(coreMod.getName());
}
```

新版本里，只要 `mods/` 下的 jar 通过 ForgeSPI 提供了下列任一服务，就会被
`ModDirTransformerDiscoverer` 收录，并被 `ModsFolderLocator` **无条件从 mod 扫描结果里排除**：

- `cpw.mods.modlauncher.api.ITransformationService`
- `net.minecraftforge.forgespi.locating.IModLocator`
- `net.minecraftforge.forgespi.locating.IDependencyLocator`
- `net.minecraftforge.fml.loading.ImmediateWindowProvider`

**没有任何"两者皆可"的开关。** 后果是模组开发者一旦实现 ForgeSPI（拿类加载优先级、做兼容修复、
注入 transformer），这个 jar 就不再作为 Mod 出现 —— 没有 `@Mod`、没有注册表、没有事件总线，
`mods.toml` 直接被忽略。

## 本模组做了什么

加载同一个 jar 里的 **CoreMod(SPI) 身份** 和 **Mod 身份**，即共存，且：

- **不修改被修复模组的一个字节**，也不需要对方重新打包（除非你选择显式打标记）；
- **不往别人的 SPI 生命周期里插队** —— 各服务的 `<clinit>`、`onLoad`、`initialize`、
  `beginScanning`、`completeScan`、`transformers()` 全部照常执行完；
- **零 JVM 启动参数**，不需要 `-javaagent`、不需要 `--add-opens/--add-exports`；
- 不依赖 `jdk.attach`，不受 `jdk.attach.allowAttachSelf` 限制。

## 使用方法

1. 把 `forgeserviceproviderinterfacefix-x.y.jar` 丢进 `mods/`；
2. 使用**带有早期窗口提供者（EarlyWindowProvider）的 Forge 版本**（见“已知限制”）；
3. 正常启动。

### 开启共存（必需）

在需要共存的那个 jar 的 `MANIFEST.MF` 里显式声明，语义直接对齐 1.12 的
`FMLCorePluginContainsFMLMod`：

```properties
Manifest-Version: 1.0
Coexist-As-Mod: true
```

Gradle 里：

```groovy
jar {
    manifest {
        attributes 'Coexist-As-Mod': 'true'
    }
}
```

判定规则很严格：**只认主属性里大小写不敏感的 `true`**。没写、写空、写 `false`、
没有 `MANIFEST.MF`，一律维持高版本原有的排除行为 —— 不会碰不需要共存的 jar。

> 本模组自身也是 SPI 提供者，所以它的构建产物已默认带上该属性（见 `build.gradle`）。
> 如果你重新打包 / 手动改过 jar 导致属性丢了，我们自己也会退回“只作为 SPI 服务存在”的状态。

### 确认是否生效

日志前缀 `[CoexEarly]`，同时写入游戏目录的 `coex-earlyhook.log`。每次判定都会输出完整依据：

```
[CoexEarly] 放行共存 jar（原被 SPI 抢占排除）: yourmod-1.0.jar
            ← 依据：命中 Coexist-As-Mod: true，mods.toml 存在  待摘=[yourmod]
[CoexEarly] 维持排除 othermod-2.3.jar  ← 依据：缺属性 Coexist-As-Mod，mods.toml 存在
[CoexEarly] 维持排除 broken-1.0.jar     ← 依据：Coexist-As-Mod=false（不是 true），mods.toml 也不存在
```

`mods.toml` 存在与否只作为诊断信息输出，**不参与判定**。

## 工作原理

### 挂载点

`ImmediateWindowProvider` 在 ForgeSPI 白名单里，因此提供它的 jar 会被拉进 **SERVICE 模块层**；
而它在 `buildLayer(SERVICE)` 之后、任何 `ITransformationService` 被实例化之前就会被
`ServiceLoader` 遍历到。本模组的实现故意返回一个不存在的 `name()`，所以只是"被实例化一下"
触发 `<clinit>`，不会抢占真正的启动窗口 provider。

```
scan() 填充 found
  → addToLayer(SERVICE) → buildLayer(SERVICE)
  → earlyInitialization → ImmediateWindowHandler.load → 本模组 <clinit> 运行   ← 我们在这里
  → ServiceLoader.load(ITransformationService) → FML onLoad / initialize / beginScanning
  → 才会首次加载 ModsFolderLocator
```

### 引导链

1. **UNCONDITIONAL `MethodHandles.Lookup`** —— 用 `ReflectionFactory.newConstructorForSerialization`
   造出 `Lookup` 的私有构造并置 `allowedModes = -1`。普通 `setAccessible` 会被
   `InaccessibleObjectException` 拒绝，而经典的"翻 `AccessibleObject.override`"在 JDK 17.0.4 上
   已被 core reflection 过滤掉。
2. **把 agent 载体定义进 bootstrap 加载器**（`ClassLoader.defineClass1`）—— 因为 JVM 解析
   `Agent-Class` 用的是**系统类加载器**，而本模组的类在 SERVICE 模块层，系统类加载器看不见。
3. **运行时生成一个只含 `MANIFEST.MF` 的 agent jar**，能力全部写在 MANIFEST 里
   （`Agent-Class` / `Can-Retransform-Classes` / `Can-Redefine-Classes`）。
4. **JNA 直调 libinstrument 的 `Agent_OnAttach`** —— 先 `JNI_GetCreatedJavaVMs` 取 `JavaVM*`，
   再调用 Attach Listener 收到 `load-agent` 命令时走的同一个入口，从而拿到 JVM 认可的
   agent 身份和真正的 `Instrumentation`。
   （注：`InstrumentationImpl.loadAgent0` 在 17.0.4 上虽能取到句柄，调用即 `InternalError`。）
5. `inst.addTransformer(...)` 注册类加载期转换器。

### 两处字节码补丁

| 目标 | 补丁 |
|---|---|
| `ModsFolderLocator.lambda$scanCandidates$1(List, Path)Z` | 排除谓词整体改为 `CoexGate.accept(excluded, p)`，带 `Coexist-As-Mod: true` 的 jar 重新进入候选池 |
| `ModuleLayerHandler.buildLayer(Layer, BiFunction)` | 入口插入 `CoexGate.purge(completedLayers, layer)`，仅在 `layer == GAME` 时动作 |

第二处是必需的：被放行的 jar 会同时存在于 SERVICE 层与 GAME 层，模块解析会抛
`ResolutionException: Module minecraft reads more than one module named xxx`，
而 JDK 不提供任何"往已建层追加模块 / 从 Configuration 删模块"的公开 API。

`purge` 的做法是**只把模块从 `Configuration.modules` 移除，保留 `nameToModule`**：

- `Configuration.resolve()` 遍历 `modules` → 解析器不再看到重复模块；
- `ModuleClassLoader.loadFromModule()` 走 `configuration.findModule()` → `nameToModule`
  → 各 SPI 服务事后（包括 `transformers()` 里现加载的 transformer 类）仍能正常按需定义。

这也是"不破坏别人 CoreMod"的关键：`transformers()` 发生在 `Launcher.run` 中
`buildLayer(GAME)` **之前**，清理动作被精确安排在这两者之间。

## 已知限制

- **仅客户端生效。** 挂载点 `ImmediateWindowHandler.load` 只在
  `forgeclient` / `forgeclientdev` / `forgeclientuserdev` 这三个 launchTarget 下执行，且受
  `earlyWindowControl` 配置影响。**请使用存在早期窗口提供者的 Forge 版本。**
  服务端支持在计划内。
- 共存意味着同一个 jar 的类会有 SERVICE / GAME 两份副本，跨副本的 `static` 状态不共享
  （1.12 单 `LaunchClassLoader` 时代不存在这个问题）。如果某个 CoreMod 与 Mod 主体共享
  静态注册表，需要按 jar 逐个确认。
- 只处理 `mods/` 下的 jar；放在 classpath / 启动参数里的模组不走这套逻辑。
- 依赖 JNA（1.20.1 的启动 classpath 已自带，经 `-DmergeModules` 合入）。

## 开发

```bash
./gradlew build
# 产物：build/libs/forgeserviceproviderinterfacefix-<version>.jar
```

主要源码：

```
early/CoexistenceWindowProvider   超早期挂载点（只被实例化，不抢 provider）
early/EarlyHook                   引导链：Lookup → boot 定义 → agent.jar → Agent_OnAttach → addTransformer
early/CoexTransformer             两处字节码补丁
coexboot/AgentBridge              agentmain 载体（被定义进 bootstrap，只引用 java.*）
coexboot/CoexGate                 放行判定 + SERVICE 层模块摘除（运行于 bootstrap 加载器）
```

## 致谢

- 动态附加与底层反射的实现思路参考自 **超级史蒂夫（plz.lizi.supersteve）** 的 `PLZBase` / `Agt`；
- [MinecraftForge](https://github.com/MinecraftForge/MinecraftForge) 与 [ModLauncher](https://github.com/cpw/modlauncher) 源码；
- 开发过程由 **Qwen3.8Flash** 协助完成。

## 许可证

MIT
