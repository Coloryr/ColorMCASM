# coloryrasmASM

通过ASM方式修改游戏，与coloryrasm通信

## 游戏内光标

控制路径沿用现有输入系统。渲染时按当前后端读取光标位置，在最终 backbuffer /
swapchain 上覆盖贴图，并把游戏仍需要的系统光标模式临时替换成隐藏模式；抓取鼠标时
不绘制。GL / Vulkan 初始化完成后才会解码和上传贴图，失败会安全退回系统光标。

在启动器中使用构建出来的 agent（已有 `-javaagent` 时替换原来的路径）：

```text
-javaagent:D:\project2026\ColorMCASM\build\libs\ColoryrASM-1.2-all.jar
```

支持入口：

- LWJGL2 `Display.swapBuffers()`（GL2）
- GLFW `glfwSwapBuffers()`（GL3）
- SDL `SDL_GL_SwapWindow()`（GL）
- GLFW Vulkan `glfwCreateWindowSurface` + `vkQueuePresentKHR`
- SDL Vulkan `SDL_Vulkan_CreateSurface` + `vkQueuePresentKHR`

LWJGL2 的 Display / Mouse / Cursor 光标调用，以及共享 GL 渲染器的调用，使用 ASM 在
初始化时生成强类型桥接类。每帧通过普通接口调用进入游戏提供的 LWJGL 方法，不再执行
`Method.invoke`、字符串方法查表或 `Object...` 参数装箱。能力检测和方法匹配仅在
初始化时进行；生成类采用 Java 8 字节码，并通过独立子类加载器对接游戏的 LWJGL，
无需开放 JDK 的 `ClassLoader.defineClass` 反射访问。

可选 JVM 参数：

- `-Dcoloryrasm.cursor.texture=绝对路径`：自定义 RGBA PNG
- `-Dcoloryrasm.cursor.hotspotX=0`：热点 X
- `-Dcoloryrasm.cursor.hotspotY=0`：热点 Y
- `-Dcoloryrasm.cursor.size=24`：光标高度（8 到 128）
- `-Dcoloryrasm.cursor.enabled=false`：禁用软件光标

默认使用 JAR 内的 `coloryrasm/cursor.png`。自定义图片最大 1024×1024，保留宽高比，
高度按窗口逻辑像素设置；热点按原始图片像素设置。高 DPI 窗口会转换到实际 framebuffer
像素。读取自定义图片失败时恢复系统光标，并在日志中输出一次该上下文的错误。

Vulkan 需要支持混合的颜色附件、可执行 graphics 的呈现队列、普通单层且无旋转的交换链，
以及一次呈现一个交换链。受保护交换链、额外的创建扩展链、共享呈现模式或不满足上述
条件的后端会保留系统光标。纹理经过 staging buffer 一次性上传，逐帧绘制不做 CPU 图像
读回，也不逐帧等待整个设备空闲。SDL 路径处理光标渲染及显隐，输入仍由现有控制模块负责。

## 构建和测试

使用 JDK 17 运行仓库内的 Gradle 8.4，编译目标为 Java 8：

```powershell
$env:JAVA_HOME = 'C:/path/to/jdk17'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
./gradlew.bat test lwjgl2Test modernTest shadowJar --console=plain
```

测试包括生成桥接的隔离类加载与直接调用检查、真实 GL3.2 core、GL2 兼容路径、
透明 PNG 和热点上传、GL 状态恢复、
真实 GLFW/Vulkan 交换链呈现和像素读回，以及 SDL OpenGL 和光标显隐。
`test` 使用 LWJGL 3.3.3，`lwjgl2Test` 使用 2.9.1，`modernTest` 使用 3.4.3。
没有图形环境的主机会跳过需要对应驱动的测试，应检查测试报告中的 skipped 数量。

本机 Windows 上三组测试实际执行通过。检查了本地 26.1.2 和官方 26.3 的后端入口，
但尚未逐个启动 Minecraft 1.7.10 至 26.3，也未覆盖所有 mod loader 和操作系统。
产物为 `build/libs/ColoryrASM-1.2-all.jar`，不打包 LWJGL、shaderc 或原生库。

修改 GLSL 或重新生成默认 PNG 后，运行 `./gradlew.bat compileCursorAssets` 再构建。
预编译的 SPIR-V 随资源提交，运行游戏无需 shaderc。
