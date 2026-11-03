# AI Person

一个本地优先的 Android 陪伴类应用。她不是客服式的 AI 助手，而是有性格、有情绪、会成长、也会冷淡和沉默的「人」。

聊天记录、记忆、人格参数和图片全部保存在本机；模型接口由你自己配置，Key 使用系统 Keystore 加密存储。

- 当前版本：0.3.1
- 包名：`qinme.aiperson`
- 最低系统：Android 8.0（API 26）
- 项目地址：https://github.com/reqinme/AI-Person

## 功能

### 角色与关系
- 每个角色有独立的人格卡、底线、接口配置和 API Key
- 亲密度分四个阶段：陌生、熟悉、亲密、依恋；不同阶段有不同语气和行为
- 亲密度显示封顶 100，内部继续累计，不会因为一次踩线就掉回原点
- 寒暄、附和（你好、在吗、早安、嗯、谢谢等）不增加亲密度
- 人格参数会随长期对话缓慢演化，关系越近，表达越贴近当前阶段

### 聊天
- 微信风格界面，支持 OriginOS 水玻璃质感，可开关
- 她会自己决定：正常回复、延迟回复、已读不回
- 延迟消息到点后，前台不打扰，后台推送通知
- 气泡显示已读状态（根据后续是否出现回复推断）
- 支持文字、图片、多模态识图、语音输入与朗读
- 会话搜索、聊天记录跳转定位
- 聊天菜单页、会话列表、底部导航、我的页

### 记忆
- 定期把对话压缩成长期记忆，按重要度分级
- 记忆会先变模糊，再被遗忘；重要的会被反复想起
- 会从对话里形成「她讨厌什么」的软印象
- 聊天记录和记忆分开管理：清空聊天不会删除记忆
- 记忆默认锁住，查看或修改需要二次确认

### 数据
- 全部数据本地存储，无云端账号
- 支持导出 / 导入 zip 备份（含聊天、记忆、人格、图片，不含 API Key）

## 技术栈

- Kotlin + Jetpack Compose + Material 3
- Navigation Compose
- Room（KSP）
- WorkManager（延迟消息与主动消息）
- OkHttp（OpenAI 兼容接口）
- Android SpeechRecognizer / TextToSpeech
- EncryptedSharedPreferences + Android Keystore

## 构建

环境要求：

- JDK 17
- Android SDK 35
- Gradle 使用项目自带 wrapper

```bash
# Debug
./gradlew assembleDebug

# Release（需要本地签名配置）
./gradlew assembleRelease
```

Release 签名读取项目根目录的 `keystore.properties`。该文件已被 `.gitignore` 忽略，不会提交到仓库：

```properties
storeFile=app/aiperson-release.jks
storePassword=你的密码
keyAlias=你的别名
keyPassword=你的密码
```

没有该文件时，Release 构建仍可完成，但不会签名。

## 配置

首次启动后，在「我的 → 接口设置」或角色设定页填写：

- Base URL，默认 `https://api.deepseek.com/v1`
- API Key
- 模型名，默认 `deepseek-chat`
- 需要识图时，打开该角色的多模态开关，并使用支持视觉的模型

任何 OpenAI 兼容的 `/chat/completions` 接口都可以接入。

## 许可

本项目使用 [AI Person 非商业源码开放协议 1.0](LICENSE)：

- 允许个人学习、研究、修改和分发
- 禁止任何商业使用（出售、广告、内购、付费订阅、收费 SaaS 等）
- 分发或二次开发时必须保留协议与版权声明，并在「关于」页或 README 显著标注原作者
- 衍生作品需继续使用同一协议并提供完整源码

由于限制商业使用，本协议属于 source-available，并非 OSI 认证的开源协议。商业授权请通过仓库联系作者。

## 署名

基于 AI Person（https://github.com/reqinme/AI-Person），原作者：reqinme

Based on AI Person (https://github.com/reqinme/AI-Person), original author: reqinme
