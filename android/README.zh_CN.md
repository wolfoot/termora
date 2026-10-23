# Termora Android 版

[English](README.md)

这是与桌面版并列的独立 Android 工程，最低支持 **Android 9**。默认安装包面向 **ARM64 手机**；提供 x86_64 构建选项供模拟器测试。按要求不包含 USB 串口。

| 功能 | 实现方式 |
|---|---|
| SSH 终端、密码/公钥、跳板机 | 复用桌面 ANSI 解析器；支持 RSA、Ed25519、ECDSA、交互认证和有序跳板机 |
| SFTP、远程编辑、权限、压缩解压 | 手机文件选择器上传下载、UTF-8 编辑、chmod、远程 tar/zip/unzip |
| 本地/远程转发、SOCKS | SSH 转发和 SOCKS5 CONNECT，前台服务维持连接 |
| 分组、标签、快捷命令、登录脚本、宏 | 原生触屏界面，宏字节与桌面版兼容 |
| 云同步、密钥管理 | Termora 账号同步、WebDAV/GitHub 加密同步、Keystore 本地保护、加密备份 |
| S3、OSS、COS、OBS、WebDAV、FTP、SMB | 原生协议客户端；对象存储配置 HTTPS 端点、存储桶及区域 |
| VNC | 软件内显示，支持键盘、剪贴板、缩放、拖动及右键 |
| RDP | **软件内嵌 FreeRDP 3.32.2**，不调用外部客户端；显示、触摸、键盘、剪贴板、硬件解码 |
| CPU、磁盘、NVIDIA GPU 监控 | 经 SSH 读取服务器数据 |
| 终端显示 | 真正等宽字体，字号和额外字间距可调，默认字间距为 0 |

## 构建与安装

在 Linux x86_64 上准备 JDK 17、Android SDK 36、NDK 28.2.13676358、CMake/Ninja 和 Python 3。进入本目录执行：

```sh
export JAVA_HOME=/你的/jdk17/目录
./scripts/build-rdp.sh arm64-v8a
./gradlew :core:test :app:lintDebug :app:assembleDebug
```

开发调试包位于 `app/build/outputs/apk/debug/app-debug.apk`。本次交付采用 `./gradlew :app:assembleRelease -PtermoraTestSigning=true` 构建，**关闭调试开关**，使用本地测试签名；副本放在项目的 `build/distributions` 下。正式发布时应由发布者配置自己的签名密钥。原生引擎采用固定版本下载和哈希校验，源码下载目录与编译产物不会加入 Git。

## 操作

主界面添加主机，点击连接，长按编辑或删除；右上角菜单进入密钥、快捷命令、宏、同步与终端设置。终端下方提供 Esc、Tab、方向键、Ctrl/Alt 和功能键；长按终端可选择、复制文本。主机配置的 SSH 选项中可设置跳板机连接顺序、登录脚本、环境变量和启动命令。

远程桌面单指点击/拖动，长按右键，双指缩放和平移；“键盘”打开输入法，“剪贴板”同步手机文本，Ctrl/Alt 和功能键在底部辅助栏。SSH 会话可直接打开文件页或服务器监控。

第三方同步使用与已修复桌面版一致的 `termora-pbe-v2` 格式，需要独立且至少 12 字符的加密密码。旧明文/CBC 同步文件需要先通过桌面版迁移。首次 SSH/RDP 连接须核对服务器指纹；已有指纹变化会拒绝连接。

## 已验证与限制

已经验证核心协议/安全测试以及 Android 9 模拟器上的 Keystore、RDP 原生库加载、Ed25519/中文终端、真实 SSH/SFTP 连接与终端界面。**未连接实际 ARM64 手机、Windows RDP 服务器、生产云账号或 FTP/SMB 服务器，不能视为这些环境的实测结果。**

VNC 支持标准 RFB 3.3/3.7/3.8、普通密码认证、Raw/CopyRect，不支持专有认证或 VeNCrypt。对象存储暂不包含分片/断点续传及厂商高级接口；从桌面导入仅含账号凭据的配置时，需要补充端点和存储桶。远程文本编辑限制 1 MiB；目录删除遵循服务端普通目录/对象标记删除规则。前台连接仍受 Android 和手机厂商的后台限制，SOCKS 不会自动成为手机全局代理。

完整构建、设备测试方法及其他兼容范围见 [English README](README.md)。
