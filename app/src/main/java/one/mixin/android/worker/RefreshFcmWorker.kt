package one.mixin.android.worker

import android.annotation.SuppressLint
import android.content.Context
import androidx.hilt.Assisted
import androidx.hilt.work.WorkerInject
import androidx.work.WorkerParameters
import com.google.firebase.iid.FirebaseInstanceId
import io.reactivex.schedulers.Schedulers
import one.mixin.android.api.request.SessionRequest
import one.mixin.android.api.service.AccountService

class RefreshFcmWorker @WorkerInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    val accountService: AccountService
) : BaseWork(context, parameters) {

    companion object {
        const val TOKEN = "token"
    }

    @SuppressLint("CheckResult")
    override suspend fun onRun(): Result {
        val token = inputData.getString(TOKEN)
        if (token != null) {
            accountService.updateSession(SessionRequest(notificationToken = token))
                .observeOn(Schedulers.io()).subscribe({}, {})
        } else {
            FirebaseInstanceId.getInstance().instanceId.addOnSuccessListener { result ->
                accountService.updateSession(SessionRequest(notificationToken = result.token))
                    .subscribeOn(Schedulers.io())
                    .observeOn(Schedulers.io()).subscribe({}, {})
            }
        }
        return Result.success()
    }
}
