package com.clu.quickanswer

import android.graphics.Bitmap
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import java.io.IOException
import java.time.Duration

class ClaudeClient(apiKey: String, model: String) : Answerer {

    private val model = Prefs.cleanModel(model).ifEmpty { Prefs.DEFAULT_CLAUDE_MODEL }

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(Prefs.cleanKey(apiKey))
        .maxRetries(1)
        .timeout(Duration.ofSeconds(30))
        .build()

    /** The image last sent to Claude (after cropping/scaling), so the user can check it. */
    @Volatile override var lastSent: Bitmap? = null
        private set

    /** The model's answer for the screenshot, or a short Arabic error message. Never throws. */
    override fun answer(bitmap: Bitmap): Reply {
        val prepared = try {
            ScreenImage.prepare(bitmap) ?: return Reply.error(ScreenImage.BLACK_SCREEN)
        } catch (e: Exception) {
            return Reply.empty("خطأ: ${e.javaClass.simpleName} ${e.message?.take(120) ?: ""}".trim())
        }
        lastSent = prepared.kept
        val image = ImageBlockParam.builder()
            .source(
                Base64ImageSource.builder()
                    .data(prepared.base64)
                    .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                    .build()
            )
            .build()
        return send(
            listOf(
                ContentBlockParam.ofImage(image),
                ContentBlockParam.ofText(TextBlockParam.builder().text(ScreenImage.PROMPT).build())
            )
        )
    }

    /** A question already read on the device: text only, no image. */
    override fun answerText(prompt: String): Reply =
        send(listOf(ContentBlockParam.ofText(TextBlockParam.builder().text(prompt).build())))

    /** One request with [blocks] as the user turn. Never throws. */
    private fun send(blocks: List<ContentBlockParam>): Reply {
        return try {
            val builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(1024L)
                .addUserMessageOfBlockParams(blocks)
                // Lowest effort = fastest answer. No thinking param (disabled thinking is a 400 on Opus 5.5).
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            if (model in FALLBACK_MODELS) {
                builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
            val message = client.messages().create(builder.build())

            if (message.stopReason().orElse(null) == StopReason.REFUSAL) return Reply.empty(REFUSED)
            val text = message.content()
                .mapNotNull { it.text().orElse(null)?.text() }
                .joinToString("")
                .trim()
            if (text.isEmpty()) Reply.empty(EMPTY) else Reply.answer(text)
        } catch (e: UnauthorizedException) {
            Reply.error("مفتاح Claude غير صالح")
        } catch (e: PermissionDeniedException) {
            Reply.error("مفتاح Claude لا يملك صلاحية لهذا النموذج")
        } catch (e: RateLimitException) {
            Reply.error("تم تجاوز حد Claude، انتظر قليلاً")
        } catch (e: NotFoundException) {
            Reply.error("اسم نموذج Claude غير صحيح")
        } catch (e: BadRequestException) {
            val msg = e.message ?: ""
            if (msg.contains("credit", ignoreCase = true) || msg.contains("billing", ignoreCase = true)) {
                Reply.error("رصيد Claude غير كافٍ، اشحن حسابك")
            } else {
                Reply.empty("Claude: ${msg.take(160)}")
            }
        } catch (e: InternalServerException) {
            Reply.error("خوادم Claude مشغولة، جرّب بعد قليل")
        } catch (e: AnthropicServiceException) {
            if (e.statusCode() == 529) Reply.error("خوادم Claude مشغولة، جرّب بعد قليل")
            else Reply.empty("خطأ Claude ${e.statusCode()}: ${e.message?.take(120) ?: ""}".trim())
        } catch (e: AnthropicIoException) {
            Reply.error("لا يوجد اتصال")
        } catch (e: IOException) {
            Reply.error("لا يوجد اتصال")
        } catch (e: Exception) {
            Reply.empty("خطأ: ${e.javaClass.simpleName} ${e.message?.take(120) ?: ""}".trim())
        }
    }

    companion object {
        private const val EMPTY = "لم يتم الحصول على إجابة"
        private const val REFUSED = "رفض النموذج الإجابة"
        private val FALLBACK_MODELS = setOf("claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")
    }
}
