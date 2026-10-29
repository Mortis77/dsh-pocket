# DSH Pocket

一个给 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（DSH）用的安卓壳。
不是投屏、也不是第二套客户端：它是一层很薄的 WebView，钉在你自托管的 DSH 远程入口上，
cookie 存在 App 里，**配对一次之后打开就是你的 dsh**。

配套的 `mobile-lite` 样式规则直接注入到页面里，所以手机上不会出现桌面那套挤成一团的布局。

## 它做什么

- **固定入口**：`DEFAULT_ORIGIN` 指向你的远程中继域名（默认是 dsh-market 的固定子域），
  地址存在 SharedPreferences，换机器时改一处即可。
- **扫码配对**：右上角「配」→「扫码」，扫电脑端配对面板的二维码；也能直接粘配对链接。
- **一份配对长期有效**：cookie 走 `CookieManager` 持久化，覆盖安装不丢，重启不丢；
  长按「配」清掉配对重来。
- **手机布局注入**：每次页面加载后注入一段 CSS + 一遍 DOM 隐藏，
  收掉大背景层、把会话头压成一行、把输入卡那排按钮放大到 36px 并去掉挤位置的插件按钮。
- **自更新**：启动 4 秒后去 `UPDATE_BASE + /version.json` 问一次，版本号更大就自己下载并弹系统安装器。

## 构建

不需要 Android Studio，也不需要 Gradle。需要的东西：

| 依赖 | 说明 |
|---|---|
| JDK 17+ | 编译 Java |
| Android SDK `platforms;android-34` | 提供 `android.jar` |
| Android SDK `build-tools;35.0.0` | `aapt2` / `d8` / `zipalign` / `apksigner`（34.0.0 的 d8 在处理匿名类时会崩，用 35） |
| `com.google.zxing:core:3.5.3` | 只用于扫码解码，放在 `app/` 外并用绝对路径引用 |

`app/build.bat` 里把 `JAVA_HOME`、`SDK`、zxing jar 的路径改成你自己的，然后：

```bat
cd app
build.bat
```

产物是 `app/dsh-pocket.apk`，debug keystore 由脚本首次运行时用 `keytool` 自动生成。

## 更新服务器

`tools/server.mjs` 是个二十行的静态服务，只放行 `version.json` 和 `dsh-pocket.apk`：

```bash
node tools/server.mjs                     # 监听 8897
cloudflared tunnel --url http://127.0.0.1:8897   # 拿一个 https 入口
```

把 `MainActivity.UPDATE_BASE` 改成那个入口，之后每次改版只要升 `versionCode` 并覆盖
`version.json`，App 下次启动就会自己更新。

> 注意：quick tunnel 的域名每次重启都会变。要长期稳定，用命名隧道（需要自己的域名），
> 或者把这个服务挂到局域网固定 IP 上。

## 已知限制

- WebView 没有实现 `DownloadListener`，所以页面里的下载链接点了没反应。
- 自更新只认 `version.json` 里的 `versionCode`，不做签名校验。
- 只做了窄屏（≤900px）的布局收敛，横屏平板不保证。

## 许可

MIT
