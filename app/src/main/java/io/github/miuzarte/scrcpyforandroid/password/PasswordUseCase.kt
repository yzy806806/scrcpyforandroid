package io.github.miuzarte.scrcpyforandroid.password

import androidx.fragment.app.FragmentActivity

class PasswordUseCase {
    suspend fun preparePassword(
        activity: FragmentActivity,
        entry: PasswordEntry,
        globalRequiresAuth: Boolean,
    ): Result<CharArray> {
        val canAuthNow = BiometricGate.canAuthenticate()

        if (globalRequiresAuth) {
            val ok = BiometricGate.authenticate(
                activity = activity,
                title = "验证以填充锁屏密码",
                subtitle = entry.name,
            )
            if (!ok) {
                return Result.failure(IllegalStateException("认证失败"))
            }
        } else if (entry.createdWithAuth.hasAuthenticatedOrigin && !canAuthNow) {
            return Result.failure(IllegalStateException("设备安全状态已变更，请重新设置密码"))
        }

        val password = entry.cipherText ?: return Result.failure(
            IllegalStateException("密码已失效，请重新设置")
        )
        return Result.success(password.copyOf())
    }
}
