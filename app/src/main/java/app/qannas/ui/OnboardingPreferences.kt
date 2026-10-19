package app.qannas.ui

import android.content.Context

/** حالة إتمام التعريف فقط؛ لا تحمل جلسة ولا تجربة ولا ترخيصاً. */
object OnboardingPreferences {
    private const val FILE_NAME = "qannas_onboarding"
    private const val KEY_COMPLETE = "complete"
    private const val KEY_VERSION = "completed_version"
    private const val CURRENT_VERSION = 2

    fun isComplete(context: Context): Boolean = runCatching {
        preferences(context).getBoolean(KEY_COMPLETE, false) &&
            preferences(context).getInt(KEY_VERSION, 0) >= CURRENT_VERSION
    }.getOrDefault(false)

    /**
     * يستعمل commit كي يعرف المستدعي، قبل تغيير المسار، إن حُفظت الحالة فعلاً.
     * لا تستدعيه شاشة التعريف؛ مالك التنقل يستدعيه بعد [OnboardingScreen] onContinue.
     */
    fun markComplete(context: Context): Boolean = runCatching {
        preferences(context)
            .edit()
            .putBoolean(KEY_COMPLETE, true)
            .putInt(KEY_VERSION, CURRENT_VERSION)
            .commit()
    }.getOrDefault(false)

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
