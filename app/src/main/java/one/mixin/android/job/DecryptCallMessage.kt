package one.mixin.android.job

import androidx.collection.ArrayMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import one.mixin.android.MixinApplication
import one.mixin.android.crypto.Base64
import one.mixin.android.db.insertAndNotifyConversation
import one.mixin.android.extension.createAtToLong
import one.mixin.android.extension.nowInUtc
import one.mixin.android.util.GsonHelper
import one.mixin.android.util.Session
import one.mixin.android.vo.CallStateLiveData
import one.mixin.android.vo.MessageCategory
import one.mixin.android.vo.MessageHistory
import one.mixin.android.vo.MessageStatus
import one.mixin.android.vo.createAckJob
import one.mixin.android.vo.createCallMessage
import one.mixin.android.webrtc.DEFAULT_TIMEOUT_MINUTES
import one.mixin.android.webrtc.answerCall
import one.mixin.android.webrtc.busy
import one.mixin.android.webrtc.cancelCall
import one.mixin.android.webrtc.candidate
import one.mixin.android.webrtc.declineCall
import one.mixin.android.webrtc.incomingCall
import one.mixin.android.webrtc.receiveInvite
import one.mixin.android.webrtc.receivePublish
import one.mixin.android.webrtc.remoteEnd
import one.mixin.android.webrtc.remoteFailed
import one.mixin.android.websocket.ACKNOWLEDGE_MESSAGE_RECEIPTS
import one.mixin.android.websocket.BlazeAckMessage
import one.mixin.android.websocket.BlazeMessageData
import one.mixin.android.websocket.LIST_PENDING_MESSAGES
import org.webrtc.IceCandidate
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.Executors

class DecryptCallMessage(
    private val callState: CallStateLiveData,
    private val lifecycleScope: CoroutineScope
) : Injector() {
    companion object {
        const val LIST_PENDING_CALL_DELAY = 2000L

        var listPendingOfferHandled = false
    }

    private val listPendingDispatcher by lazy {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    }

    private val listPendingJobMap = ArrayMap<String, Pair<Job, BlazeMessageData>>()

    private val listPendingCandidateMap = ArrayMap<String, ArrayList<IceCandidate>>()

    fun onRun(data: BlazeMessageData) {
        try {
            syncConversation(data)
            if (isExistMessage(data.messageId)) {
                updateRemoteMessageStatus(data.messageId, MessageStatus.DELIVERED)
            } else if (data.category.startsWith("WEBRTC_")) {
                processWebRTC(data)
            } else if (data.category.startsWith("KRAKEN_")) {
                processKraken(data)
            } else {
                updateRemoteMessageStatus(data.messageId, MessageStatus.DELIVERED)
            }
        } catch (e: Exception) {
            Timber.e("DecryptCallMessage failure, $e")
            updateRemoteMessageStatus(data.messageId, MessageStatus.DELIVERED)
        }
    }

    private fun processKraken(data: BlazeMessageData) {
        val ctx = MixinApplication.appContext
        Timber.d("@@@ processKraken category: ${data.category}")
        if (data.category == MessageCategory.KRAKEN_PUBLISH.name) {
            syncUser(data.userId)?.let { user ->
                receivePublish(ctx, user, data)
            }
        } else if (data.category == MessageCategory.KRAKEN_INVITE.name) {
            syncUser(data.userId)?.let { user ->
                receiveInvite(ctx, data, arrayListOf(user))
            }
        }
        notifyServer(data)
    }

    private fun processWebRTC(data: BlazeMessageData) {
        if (data.source == LIST_PENDING_MESSAGES && data.category == MessageCategory.WEBRTC_AUDIO_OFFER.name) {
            val isExpired = try {
                val offset = System.currentTimeMillis() - data.createdAt.createAtToLong()
                offset > DEFAULT_TIMEOUT_MINUTES * 58 * 1000
            } catch (e: NumberFormatException) {
                true
            }
            if (!isExpired && !listPendingOfferHandled) {
                listPendingJobMap[data.messageId] = Pair(
                    lifecycleScope.launch(listPendingDispatcher) {
                        delay(LIST_PENDING_CALL_DELAY)
                        listPendingOfferHandled = true
                        listPendingJobMap.forEach { entry ->
                            val pair = entry.value
                            val job = pair.first
                            val curData = pair.second
                            if (entry.key != data.messageId && !job.isCancelled) {
                                job.cancel()
                                val m = createCallMessage(
                                    UUID.randomUUID().toString(), curData.conversationId, Session.getAccountId()!!,
                                    MessageCategory.WEBRTC_AUDIO_BUSY.name, null, nowInUtc(), MessageStatus.SENDING.name, curData.messageId
                                )
                                jobManager.addJobInBackground(SendMessageJob(m, recipientId = curData.userId))

                                val savedMessage = createCallMessage(
                                    curData.messageId, m.conversationId, curData.userId, m.category, m.content,
                                    m.createdAt, curData.status, m.quoteMessageId
                                )
                                database.insertAndNotifyConversation(savedMessage)
                                listPendingCandidateMap.remove(curData.messageId, listPendingCandidateMap[curData.messageId])
                            }
                        }
                        processCall(data)
                        listPendingJobMap.clear()
                    },
                    data
                )
            } else if (isExpired) {
                val message = createCallMessage(
                    data.messageId, data.conversationId, data.userId, MessageCategory.WEBRTC_AUDIO_CANCEL.name,
                    null, data.createdAt, data.status
                )
                database.insertAndNotifyConversation(message)
            }
            notifyServer(data)
        } else {
            processCall(data)
        }
    }

    private fun processCall(data: BlazeMessageData) {
        val ctx = MixinApplication.appContext
        if (data.category == MessageCategory.WEBRTC_AUDIO_OFFER.name) {
            syncUser(data.userId)?.let { user ->
                val pendingCandidateList = listPendingCandidateMap[data.messageId]
                if (pendingCandidateList == null || pendingCandidateList.isEmpty()) {
                    incomingCall(ctx, user, data)
                } else {
                    incomingCall(ctx, user, data, GsonHelper.customGson.toJson(pendingCandidateList.toArray()))
                    pendingCandidateList.clear()
                    listPendingCandidateMap.remove(data.messageId, pendingCandidateList)
                }
                notifyServer(data)
            }
        } else if (listPendingJobMap.containsKey(data.quoteMessageId)) {
            listPendingJobMap[data.quoteMessageId]?.let { pair ->
                if (data.source == LIST_PENDING_MESSAGES && data.category == MessageCategory.WEBRTC_ICE_CANDIDATE.name) {
                    val json = String(Base64.decode(data.data))
                    val ices = GsonHelper.customGson.fromJson(json, Array<IceCandidate>::class.java)
                    var list = listPendingCandidateMap[data.quoteMessageId]
                    if (list == null) {
                        list = arrayListOf()
                    }
                    list.addAll(ices)
                    listPendingCandidateMap[data.quoteMessageId] = list
                    return@let
                }

                pair.first.let {
                    if (!it.isCancelled) {
                        it.cancel()
                    }
                }
                listPendingJobMap.remove(data.quoteMessageId)

                val message = createCallMessage(
                    data.quoteMessageId!!, data.conversationId, data.userId,
                    MessageCategory.WEBRTC_AUDIO_CANCEL.name, null, data.createdAt, data.status
                )
                database.insertAndNotifyConversation(message)
            }
            notifyServer(data)
        } else {
            when (data.category) {
                MessageCategory.WEBRTC_AUDIO_ANSWER.name -> {
                    if (callState.isIdle() || data.quoteMessageId != callState.trackId) {
                        notifyServer(data)
                        return
                    }
                    answerCall(ctx, data)
                }
                MessageCategory.WEBRTC_ICE_CANDIDATE.name -> {
                    if (callState.isIdle() || data.quoteMessageId != callState.trackId) {
                        notifyServer(data)
                        return
                    }
                    candidate(ctx, data)
                }
                MessageCategory.WEBRTC_AUDIO_CANCEL.name -> {
                    if (callState.isIdle()) {
                        notifyServer(data)
                        return
                    }
                    saveCallMessage(data)
                    if (data.quoteMessageId != callState.trackId) {
                        return
                    }
                    cancelCall(ctx)
                }
                MessageCategory.WEBRTC_AUDIO_DECLINE.name -> {
                    if (callState.isIdle()) {
                        notifyServer(data)
                        return
                    }

                    val uId = getUserId()
                    saveCallMessage(data, userId = uId)
                    if (data.quoteMessageId != callState.trackId) {
                        return
                    }
                    declineCall(ctx)
                }
                MessageCategory.WEBRTC_AUDIO_BUSY.name -> {
                    if (callState.isIdle() || data.quoteMessageId != callState.trackId ||
                        callState.user == null
                    ) {
                        notifyServer(data)
                        return
                    }

                    saveCallMessage(data, userId = Session.getAccountId()!!)
                    busy(ctx)
                }
                MessageCategory.WEBRTC_AUDIO_END.name -> {
                    if (callState.isIdle()) {
                        notifyServer(data)
                        return
                    }

                    val duration = System.currentTimeMillis() - callState.connectedTime!!
                    saveCallMessage(data, duration = duration.toString(), userId = getUserId(), status = MessageStatus.READ.name)
                    remoteEnd(ctx)
                }
                MessageCategory.WEBRTC_AUDIO_FAILED.name -> {
                    if (callState.isIdle()) {
                        notifyServer(data)
                        return
                    }

                    val uId = getUserId()
                    saveCallMessage(data, userId = uId)
                    remoteFailed(ctx)
                }
            }
            notifyServer(data)
        }
    }

    private fun getUserId(): String {
        return if (callState.isOffer) {
            Session.getAccountId()!!
        } else {
            callState.user!!.userId
        }
    }

    private fun notifyServer(data: BlazeMessageData) {
        updateRemoteMessageStatus(data.messageId, MessageStatus.READ)
        messageHistoryDao.insert(MessageHistory(data.messageId))
    }

    private fun updateRemoteMessageStatus(messageId: String, status: MessageStatus = MessageStatus.DELIVERED) {
        jobDao.insert(createAckJob(ACKNOWLEDGE_MESSAGE_RECEIPTS, BlazeAckMessage(messageId, status.name)))
    }

    private fun saveCallMessage(
        data: BlazeMessageData,
        category: String? = null,
        duration: String? = null,
        userId: String = data.userId,
        status: String? = null
    ) {
        if (data.userId == Session.getAccountId()!! || data.quoteMessageId == null) {
            return
        }
        var messageStatus = data.status
        status?.let {
            messageStatus = status
        }
        val realCategory = category ?: data.category
        val message = createCallMessage(
            data.quoteMessageId, data.conversationId, userId, realCategory,
            null, data.createdAt, messageStatus, mediaDuration = duration
        )
        database.insertAndNotifyConversation(message)
    }
}
