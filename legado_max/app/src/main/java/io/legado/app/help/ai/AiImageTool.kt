package io.legado.app.help.ai

import org.json.JSONArray
import org.json.JSONObject

object AiImageTool {
    fun resolvedTools(): List<AiResolvedTool> = listOf(
        AiResolvedTool(
            name = "generate_image",
            definition = JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "generate_image")
                    put("description", "Generate an image and return an image url or data url.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("prompt", JSONObject().apply {
                                put("type", "string")
                                put("description", "Image prompt")
                            })
                            put("providerId", JSONObject().apply {
                                put("type", "string")
                                put("description", "Optional image provider id. Use only when user explicitly selects an image model; otherwise omit it.")
                            })
                            put("size", JSONObject().apply {
                                put("type", "string")
                                put("description", "Optional. Image size as WIDTHxHEIGHT. Size档位(1K/2K/3K/4K) × ratio → exact pixels:\n1:1: 1024x1024 / 2048x2048 / 3072x3072 / 4096x4096\n3:4: 864x1152 / 1728x2304 / 2592x3456 / 3456x4608\n4:3: 1152x864 / 2304x1728 / 3456x2592 / 4608x3456\n16:9: 1312x736 / 2624x1472 / 3936x2208 / 5248x2944\n9:16: 736x1312 / 1472x2624 / 2208x3936 / 2944x5248\n2:3: 832x1248 / 1664x2496 / 2496x3744 / 3328x4992\n3:2: 1248x832 / 2496x1664 / 3744x2496 / 4992x3328\n21:9: 1568x672 / 3136x1344 / 4704x2016 / 6272x2688\nIf omitted, provider default is used. Unsupported sizes may be mapped by the server.")
                            })
                        })
                        put("required", JSONArray().put("prompt"))
                    })
                })
            },
            execute = { args ->
                val prompt = args?.optString("prompt").orEmpty().trim()
                if (prompt.isBlank()) {
                    JSONObject().put("ok", false).put("success", false).put("error", "prompt is empty").toString()
                } else {
                    val providerId = args?.optString("providerId").orEmpty().trim()
                    val size = args?.optString("size").orEmpty().trim().takeIf { it.isNotBlank() }
                    val provider = if (providerId.isBlank()) {
                        null
                    } else {
                        AiImageService.providerByIdOrNull(providerId)
                    }
                    if (providerId.isNotBlank() && provider == null) {
                        JSONObject()
                            .put("ok", false)
                            .put("success", false)
                            .put("error", "image provider is unavailable: $providerId")
                            .toString()
                    } else {
                        val targetProvider = provider ?: AiImageService.currentProviderOrNull()
                        runCatching {
                            val image = AiImageService.generateAndStore(
                                prompt,
                                provider,
                                metadata = AiImageGalleryManager.ImageMetadata(
                                    sourceType = AiImageGalleryManager.SOURCE_TYPE_CHAT,
                                    sourceText = prompt
                                ),
                                size = size
                            )
                            JSONObject()
                                .put("ok", true)
                                .put("success", true)
                                .put("type", "image")
                                .put("imageId", image.id)
                                .put("imagePath", image.localPath)
                                .put("name", image.name)
                                .put("prompt", prompt)
                                .put("provider", image.providerName)
                                .put("model", image.model)
                                .toString()
                        }.getOrElse {
                            JSONObject()
                                .put("ok", false)
                                .put("success", false)
                                .put("error", it.localizedMessage ?: it.javaClass.simpleName)
                                .apply {
                                    targetProvider?.let { current ->
                                        put("provider", current.displayName())
                                        put("providerType", current.type)
                                        put("baseUrl", current.baseUrl)
                                        put("model", current.model)
                                    }
                                }
                                .toString()
                        }
                    }
                }
            }
        ),
        AiResolvedTool(
            name = "edit_image",
            definition = JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "edit_image")
                    put(
                        "description",
                        "Edit/modify an existing image based on a prompt. " +
                            "Use when the user asks to modify, edit, or transform an already-existing image " +
                            "(e.g. \"把背景换成海滩\", \"去掉图里的文字\", \"让这张图变成水彩风格\"). " +
                            "Do NOT use for generating a brand-new image from scratch — use generate_image for that. " +
                            "The source image is identified by its ai-image:// reference."
                    )
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("image", JSONObject().apply {
                                put("type", "string")
                                put(
                                    "description",
                                    "Source image reference in the form ai-image://{id}. " +
                                        "Must be a reference previously shown in the conversation " +
                                        "(from a user upload or a prior generate_image result's imageRef field)."
                                )
                            })
                            put("prompt", JSONObject().apply {
                                put("type", "string")
                                put("description", "Edit instruction describing how to modify the image.")
                            })
                            put("providerId", JSONObject().apply {
                                put("type", "string")
                                put("description", "Optional image provider id. Use only when user explicitly selects an image model; otherwise omit it.")
                            })
                            put("size", JSONObject().apply {
                                put("type", "string")
                                put("description", "Optional. Output size as WIDTHxHEIGHT. Same size tiers as generate_image. If omitted, provider default is used.")
                            })
                        })
                        put("required", JSONArray().put("image").put("prompt"))
                    })
                })
            },
            execute = { args ->
                val imageRef = args?.optString("image").orEmpty().trim()
                val prompt = args?.optString("prompt").orEmpty().trim()
                if (imageRef.isBlank() || !imageRef.startsWith("ai-image://")) {
                    JSONObject()
                        .put("ok", false)
                        .put("success", false)
                        .put("error", "image must be an ai-image:// reference")
                        .toString()
                } else if (prompt.isBlank()) {
                    JSONObject()
                        .put("ok", false)
                        .put("success", false)
                        .put("error", "prompt is empty")
                        .toString()
                } else {
                    val providerId = args?.optString("providerId").orEmpty().trim()
                    val size = args?.optString("size").orEmpty().trim().takeIf { it.isNotBlank() }
                    val provider = if (providerId.isBlank()) {
                        null
                    } else {
                        AiImageService.providerByIdOrNull(providerId)
                    }
                    if (providerId.isNotBlank() && provider == null) {
                        JSONObject()
                            .put("ok", false)
                            .put("success", false)
                            .put("error", "image provider is unavailable: $providerId")
                            .toString()
                    } else {
                        val targetProvider = provider ?: AiImageService.currentProviderOrNull()
                        runCatching {
                            val image = AiImageService.editAndStore(
                                sourceImageRef = imageRef,
                                prompt = prompt,
                                provider = provider,
                                metadata = AiImageGalleryManager.ImageMetadata(
                                    sourceType = AiImageGalleryManager.SOURCE_TYPE_CHAT,
                                    sourceText = prompt
                                ),
                                size = size
                            )
                            JSONObject()
                                .put("ok", true)
                                .put("success", true)
                                .put("type", "image")
                                .put("imageId", image.id)
                                .put("imageRef", AiImageGalleryManager.imageUri(image.id))
                                .put("imagePath", image.localPath)
                                .put("name", image.name)
                                .put("prompt", prompt)
                                .put("provider", image.providerName)
                                .put("model", image.model)
                                .put("sourceImageRef", imageRef)
                                .toString()
                        }.getOrElse {
                            JSONObject()
                                .put("ok", false)
                                .put("success", false)
                                .put("error", it.localizedMessage ?: it.javaClass.simpleName)
                                .apply {
                                    targetProvider?.let { current ->
                                        put("provider", current.displayName())
                                        put("providerType", current.type)
                                        put("baseUrl", current.baseUrl)
                                        put("model", current.model)
                                    }
                                }
                                .toString()
                        }
                    }
                }
            }
        )
    )
}
