# Atoll Android 安装与使用

安装文件：频道交付目录中的 `atoll-android-release.apk`。这是开启 R8 压缩的
release 包，当前使用 debug 证书签名。支持 Android 8.0 及以上。

## 安装

把 APK 下载到手机，用文件管理器点开，按安装器提示允许该来源安装应用，完成后打开
Atoll。也可以从电脑运行 `adb install -r atoll-android-release.apk`。

## 登录

先在手机上连接你的 Tailscale 网络。登录页填写：

- 服务器地址：`https://tardis.tail6bc2a1.ts.net`
- 账号：你在该节点上的账号；使用 root 时填 `root`。
- 密码：该账号的密码。root 密码使用节点上的 `atoll passwd` 设置。

点“进入 Atoll”，连接状态显示 OPEN 后选择频道。APK 不包含密码、会话或测试节点凭据。
本次自动验收只连接自建测试节点，没有用交付 APK 测试你的节点。

## 导出性能记录

打开一个你可写的频道，确认连接为 OPEN，复现卡顿或滑动问题；点右上角“⋯”中的
“导出性能记录”，也可以从“设置”里的“诊断与性能”导出。

看到“已导出到频道文件：atoll-perf-…json”后，用“⋯ → 打开文件”找到该 JSON。
记录保存在当前频道的文件目录中，可下载或作为频道附件提供。它用于查看启动、
列表显示、帧耗时和滚动记录，不需要开启 USB 调试。
