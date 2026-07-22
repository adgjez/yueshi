# AI 图生图（edit_image）工具设计

日期：2026-07-22
分支：trae/ai-edit-image-tool

## 背景与目标

用户在 AI 聊天里要求"修改这张图"时，当前只能文生图（`generate_image`），无法基于已有图片做编辑。新增 `edit_image` 工具，让模型在用户要求修改图片时调用它，把源图与编辑指令一起发给图片服务的 `/images/edits` 端点，生成新图。

## 关键约束

1. 工具 `execute` 回调签名是 `suspend (JSONObject?) -> String`，只能拿到 `args`，拿不到会话上下文。因此源图必须由模型在 `args.image` 里显式传入 `ai-image://{id}` 引用。
2. 模型在多轮对话里需要"知道"有哪些图片可供编辑。当前 `generate_image` 结果只回传 `imageId`/`imagePath`，模型并不知道 `ai-image://` handle；用户上传图的引用也存在 USER 消息的 `imageRefs` 字段里，模型同样看不到 handle。需要在对话构造时把引用 handle 暴露给模型。
3. 回传 data URL 给模型不可行（单张 1024px JPEG base64 就有几万 token，撑爆上下文）。用 `ai-image://` 引用是轻量方案。

## 设计

### 1. 工具定义

在 `AiImageTool.resolvedTools()` 新增第二个 `AiResolvedTool`，与 `generate_image` 并列：

```
edit_image(image, prompt, size?, providerId?)
```

- `image`（必填，string）：要编辑的源图引用，格式 `ai-image://{id}`。
- `prompt`（必填，string）：编辑指令（如"把背景换成海滩"、"去掉图里的文字"）。
- `size`（可选，string）：输出尺寸 `WIDTHxHEIGHT`，复用 `generate_image` 的 size 档位参考表。省略用 provider 默认。
- `providerId`（可选，string）：指定图片 provider，仅在用户明确选择某图源时用。

工具 description 引导模型在"用户要求修改/编辑已有图片"时调用，而非文生图场景。

### 2. 源图引用来源

模型通过两个途径拿到 `ai-image://` handle：

**a) AI 生成图**：`generate_image` 工具返回的结果 JSON 新增 `imageRef` 字段（`"ai-image://{id}"`）。模型生成图后下一轮即可引用该 handle 调 `edit_image`。

**b) 用户上传图**：改造 `AiChatService.buildUserMultimodalContent`，在构造完 text + image_url 部件后，若 `imageRefs` 非空，追加一条文本部件 `{"type":"text","text":"图片引用：ai-image://a, ai-image://b"}`。这样模型既能"看到"图片像素（image_url data URL），也能拿到可引用的 handle。

chat_completions 与 responses 两条路径都兼容：responses 经 `appendResponsesMessage` 把多模态 content 数组里的 text 部件转成 `input_text`，引用标注原样透传。

### 3. AiImageService 编辑实现

新增方法：

- `editAndStore(sourceImageRef, prompt, provider, metadata, size): AiGeneratedImage`
  - 解析 provider（复用 `resolveProvider`）
  - 用 `AiImageGalleryManager.resolveImageFile(sourceImageRef)` 拿到源图 File，缺失则报错
  - 调 `editByImagesApi` 拿到 `ImageGenerationResult`
  - 用 `AiImageGalleryManager.saveGeneratedImage` 落盘，sourceType=SOURCE_TYPE_CHAT，originalSource 记录源图 ref

- `editByImagesApi(sourceFile, prompt, provider, baseUrl, params, size): ImageGenerationResult`
  - OkHttp `MultipartBody.Builder`：
    - `model`：provider.model 或 params.model 或默认 `gpt-image-1`
    - `prompt`：编辑指令
    - `image`：源图文件，按扩展名定 mime（jpg→image/jpeg, png→image/png, webp→image/webp, 其余 image/jpeg）
    - `n`：1
    - `size`：默认 `1024x1024`，调用方 size 覆盖
  - POST `{baseUrl}/images/edits`
  - 鉴权头、自定义 headers 复用 `generateByImagesApi` 的逻辑
  - 响应解析复用现有 `imageFromOpenAiResponse`
  - 失败走 `logRequest` 记日志后抛

- JS provider：直接报错「当前图片源(JS)不支持图生图」。`editAndStore` 在 `resolveProvider` 后判断 `target.type == TYPE_JS` 时抛错。

不支持 mask/inpainting（YAGNI）。

### 4. edit_image 工具 execute 逻辑

```
val imageRef = args.image (必填，校验非空 + 以 "ai-image://" 开头)
val prompt = args.prompt (必填，校验非空)
val size = args.size (可选)
val providerId = args.providerId (可选)
→ AiImageService.editAndStore(imageRef, prompt, provider, metadata, size)
→ 返回 JSON: ok/success/type=image/imageId/imageRef/imagePath/name/prompt/provider/model/sourceImageRef
```

错误处理结构同 `generate_image`：provider 不可用、prompt 为空、源图缺失、provider 不支持图生图、生图失败等情况分别返回 `ok=false` JSON。

### 5. UI 与注册接入

- `AiChatUiMapper.parseToolDisplayPayload` 新增 `"edit_image"` 分支，复用 `parseImageResult` 解析，title 用"AI 图片编辑"，渲染图片结果卡片（与 generate_image 一致）。
- `AiToolRegistry.characterCompanionToolNames` 新增 `"edit_image"`（角色 companion 也能编辑图）。

## 改动文件

| 文件 | 改动 |
|---|---|
| `help/ai/AiImageTool.kt` | 新增 edit_image 工具；generate_image 结果增 imageRef |
| `help/ai/AiImageService.kt` | editAndStore / editByImagesApi |
| `help/ai/AiChatService.kt` | buildUserMultimodalContent 注入引用标注 |
| `ui/main/ai/compose/AiChatUiMapper.kt` | edit_image 卡片分支 |
| `help/ai/AiToolRegistry.kt` | characterCompanionToolNames 增 edit_image |

## 不做（YAGNI）

- mask / inpainting 局部重绘
- responses 端点的图生图（responses 的 image_generation tool 本质是参考生图非真编辑）
- 批量编辑（n>1）
- 编辑历史记录/撤销链
