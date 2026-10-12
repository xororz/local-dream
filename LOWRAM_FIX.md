# local-dream 低内存模式无法关闭 Bug 修复说明

基于 **nightmare-mobile 1.6.140** 的实现思路（`Prefs.kt` 的 `lowRamDefault` /
`lowRamFor`，以及 `HarnessViewModel.chooseLowRam` 的注释结论："`--lowram`
binds at LAUNCH"），修复 local-dream 3.0.0-alpha.5 中低内存模式关闭后不生效的问题。

## 根因

`BackendService` 使用一个长驻的 native 后端进程服务模型：

- 启动命令（包括 `--lowram` / `--anima_seq_dit` / `--no_img2img`）在
  `startBackend()` 里根据 SharedPreferences **进程启动那一刻**拼好；
- `reconcile()` 用 `serving == want`（`BackendConfig` 相等）决定是否复用仍存活的
  进程，而 `BackendConfig` 只包含 `modelId/backendType/width/height/listenOnAll`，
  **不含任何启动期开关**。

因此当后端进程仍存活时（主机模式 Host Mode 下服务常驻、或 1.5s 停止宽限期内重新
进入生成页），用户在设置里关闭"低内存模式"后，下一次启动请求的配置与 `serving`
完全相等，`reconcile()` 直接复用旧的、仍带 `--lowram` 的进程——开关写了也没用，
表现为"低内存模式无法关闭"。

另外 UI 首次进入设置时会把 `sdxl_lowram` / `anima_lowram` **写死为 true**（缺省值
恒为 true），16GB 大内存设备也默认处于慢速、无预览的低内存模式，与提示文案
"小于 16GB 内存的设备建议开启"矛盾，加重了"关不掉"的观感。

## 修复内容（对照 nightmare 的思路）

0. **【真正的拦路虎】后端二进制链接了 libomp.so 导致无法启动**
   - 现象（设备截图）：`CANNOT LINK EXECUTABLE ".../libstable_diffusion_core.so":
     library "libomp.so" not found: needed by main executable` —— 进程压根
     起不来，任何模型都会"后端启动失败"；
   - 根因：tokenizers-cpp 内置的 sentencepiece 会 `find_package(OpenMP)`，在本机
     NDK 工具链下命中 clang 自带的 libomp.so 并链接进去；Android 设备没有
     libomp.so，动态链接器直接拒绝执行（官方发布版无此依赖，本机所有历史构建
     包括 alpha.3 都有此问题）；
   - 修复：`app/src/main/cpp/CMakeLists.txt` 增加
     `set(CMAKE_DISABLE_FIND_PACKAGE_OpenMP TRUE CACHE BOOL "" FORCE)`，
     libsais 退回串行（与官方构建一致），DT_NEEDED 与官方完全一致
     （libandroid/libdl/libm/libc/libGLESv2/libEGL/liblog，无 libomp）。

1. **开关纳入 `BackendConfig`（对应 nightmare 的 "`--lowram` binds at LAUNCH"）**
   - `BackendConfig` 增加 `useImg2img` / `sdxlLowRam` / `animaLowRam` /
     `animaSeqDit` 四个字段；
   - `parseConfig()` 在每次启动请求时**现读** SharedPreferences（对应 nightmare
     "Read from storage at every LAUNCH rather than from a loaded copy"），
     构造 config；
   - `startBackend()` 不再自行读 prefs，统一使用 config，保证命令行与
     `reconcile()` 决策一致。
   - 效果：只要开关发生变化，`serving == want` 判定失败 → `reconcile()` 重启后端
     进程、用新参数重新拼命令 → 下一次 Run（或主机模式的 /select）即生效。
     `use_img2img` 也顺带修掉了同一类的陈旧进程复用问题。
   - ⚠ 默认值保持上游的"开启"（安全侧）：本修复解决的是"开关生效"，而不是
     改变默认值。

2. **低内存模式关闭后模型加载失败的兜底修复（nightmare 的 SpillFill 教训）**
   - 上游 alpha.5 在关闭低内存模式后，会把 SDXL 的 UNet/VAE 注册进一个
     **硬编码 920 MiB 的 HTP spill-fill 共享组**，且**没有回退**：第三方工具链
     转换的 checkpoint 若声明更大的 spill-fill 需求，QNN 拒绝注册 → 后端启动
     失败。
   - 修复：`QnnRuntime.hpp` 恢复 spill-fill 元数据读取与
     `createModelWithOptionalGroup`（组被拒绝时自动改用独立 context 重试）；
     `PipelineSdxl.hpp` / `PipelineAnima.hpp` 的组大小优先取模型自身声明、
     拒绝回退独立、成员只在 head 真正成组时入组。

3. **错误信息不再被吞掉（诊断改进）**
   - `BackendService` 监控线程保留后端进程最近 15 行输出，异常退出时附在
     Error 状态里；`checkBackendHealth` / 运行页显示**真实错误**（超时才退回
     通用提示）。正是这一改进让上面的 libomp 问题直接显示在了页面上。

## 修改文件

- `app/src/main/cpp/CMakeLists.txt`（禁用 OpenMP 查找，修复 libomp 链接）
- `app/src/main/java/io/github/xororz/localdream/service/BackendService.kt`
- `app/src/main/java/io/github/xororz/localdream/ui/screens/ModelListScreen.kt`
- `app/src/main/java/io/github/xororz/localdream/ui/screens/ModelRunSupport.kt`
- `app/src/main/java/io/github/xororz/localdream/ui/screens/ModelRunScreen.kt`
- `app/src/main/cpp/src/QnnRuntime.hpp`
- `app/src/main/cpp/src/PipelineSdxl.hpp`
- `app/src/main/cpp/src/PipelineAnima.hpp`

## 行为差异与注意事项

- 切换低内存开关后，**下一次进入生成页（或主机模式下下一次 /select）时后端自动
  用新参数重启**。与 nightmare 的 `chooseLowRam`（立即停止常驻后端）等价效果，
  但更温和：不会打断主机模式下正在进行中的一次生成。
- 低内存模式默认开启（与上游一致）；用户手动切换过的值始终以 SharedPreferences
  为准。关闭后个别拒绝共享 spill-fill 的 checkpoint 会自动退回独立缓冲区
  （慢一点、内存多 ~600MB，但一定可加载）。
- 如仍有模型加载失败：页面现在会显示后端的具体报错，截图发来即可定位；
  也可以 `adb logcat -s BackendService -d > backend.log` 抓完整日志。



