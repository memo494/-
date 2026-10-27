package com.clu.quickanswer

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The three steps shown on the first launch (see MainActivity.maybeShowWelcome): the app icon on top,
 * then the title and the text centered. Kept apart from the activity so the screenshot test draws the
 * same text. The caller adds the button and what it does.
 */
object WelcomeDialog {
    const val TITLE = "أهلًا بك في Clu"
    const val MESSAGE = "1) الصق مفتاح Gemini المجاني (الرابط تحت).\n" +
        "2) اضغط «منح صلاحية الظهور فوق التطبيقات».\n" +
        "3) اضغط «تشغيل» وبعدين المس الفقاعة أو زر خفض الصوت لما يطلع السؤال."
    const val BUTTON = "يلا نبلّش"

    fun builder(context: Context): MaterialAlertDialogBuilder =
        MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_Clu_MaterialAlertDialog_Welcome)
            .setIcon(R.drawable.ic_clu_logo)
            .setTitle(TITLE)
            .setMessage(MESSAGE)
}
