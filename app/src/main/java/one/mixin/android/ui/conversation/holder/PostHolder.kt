package one.mixin.android.ui.conversation.holder

import android.graphics.Color
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import kotlinx.android.synthetic.main.date_wrapper.view.*
import kotlinx.android.synthetic.main.item_chat_action.view.chat_name
import kotlinx.android.synthetic.main.item_chat_post.view.*
import one.mixin.android.R
import one.mixin.android.extension.maxItemWidth
import one.mixin.android.extension.timeAgoClock
import one.mixin.android.ui.conversation.adapter.ConversationAdapter
import one.mixin.android.ui.style.MarkwonUtil
import one.mixin.android.vo.MessageItem
import org.jetbrains.anko.dip

class PostHolder constructor(containerView: View) : BaseViewHolder(containerView) {
    init {
        itemView.chat_layout.setMaxWidth(itemView.context.maxItemWidth())
    }

    override fun chatLayout(isMe: Boolean, isLast: Boolean, isBlink: Boolean) {
        super.chatLayout(isMe, isLast, isBlink)
        val lp = (itemView.chat_layout.layoutParams as ConstraintLayout.LayoutParams)
        if (isMe) {
            lp.horizontalBias = 1f
            if (isLast) {
                setItemBackgroundResource(
                    itemView.chat_layout,
                    R.drawable.chat_bubble_me_last,
                    R.drawable.chat_bubble_me_last_night
                )
            } else {
                setItemBackgroundResource(
                    itemView.chat_layout,
                    R.drawable.chat_bubble_me,
                    R.drawable.chat_bubble_me_night
                )
            }
        } else {
            lp.horizontalBias = 0f
            if (isLast) {
                setItemBackgroundResource(
                    itemView.chat_layout,
                    R.drawable.chat_bubble_other_last,
                    R.drawable.chat_bubble_other_last_night
                )
            } else {
                setItemBackgroundResource(
                    itemView.chat_layout,
                    R.drawable.chat_bubble_other,
                    R.drawable.chat_bubble_other_night
                )
            }
        }
    }

    private var onItemListener: ConversationAdapter.OnItemListener? = null

    fun bind(
        messageItem: MessageItem,
        isLast: Boolean,
        isFirst: Boolean = false,
        hasSelect: Boolean,
        isSelect: Boolean,
        onItemListener: ConversationAdapter.OnItemListener
    ) {
        this.onItemListener = onItemListener
        if (hasSelect && isSelect) {
            itemView.setBackgroundColor(SELECT_COLOR)
        } else {
            itemView.setBackgroundColor(Color.TRANSPARENT)
        }

        itemView.chat_tv.setOnLongClickListener {
            if (!hasSelect) {
                onItemListener.onLongClick(messageItem, adapterPosition)
            } else {
                onItemListener.onSelect(!isSelect, messageItem, adapterPosition)
                true
            }
        }

        itemView.chat_tv.setOnClickListener {
            if (hasSelect) {
                onItemListener.onSelect(!isSelect, messageItem, adapterPosition)
            } else {
                onItemListener.onPostClick(messageItem)
            }
        }
        itemView.chat_layout.setOnClickListener {
            if (!hasSelect) {
                onItemListener.onPostClick(messageItem)
            }
        }

        MarkwonUtil.getSingle().setMarkdown(itemView.chat_tv, messageItem.content!!)

        itemView.setOnLongClickListener {
            if (!hasSelect) {
                onItemListener.onLongClick(messageItem, adapterPosition)
            } else {
                onItemListener.onSelect(!isSelect, messageItem, adapterPosition)
                true
            }
        }

        itemView.setOnClickListener {
            if (hasSelect) {
                onItemListener.onSelect(!isSelect, messageItem, adapterPosition)
            }
        }

        val isMe = meId == messageItem.userId
        if (isFirst && !isMe) {
            itemView.chat_name.visibility = View.VISIBLE
            itemView.chat_name.text = messageItem.userFullName
            if (messageItem.appId != null) {
                itemView.chat_name.setCompoundDrawables(null, null, botIcon, null)
                itemView.chat_name.compoundDrawablePadding = itemView.dip(3)
            } else {
                itemView.chat_name.setCompoundDrawables(null, null, null, null)
            }
            itemView.chat_name.setTextColor(getColorById(messageItem.userId))
            itemView.chat_name.setOnClickListener { onItemListener.onUserClick(messageItem.userId) }
        } else {
            itemView.chat_name.visibility = View.GONE
        }

        if (messageItem.appId != null) {
            itemView.chat_name.setCompoundDrawables(null, null, botIcon, null)
            itemView.chat_name.compoundDrawablePadding = itemView.dip(3)
        } else {
            itemView.chat_name.setCompoundDrawables(null, null, null, null)
        }
        itemView.chat_time.timeAgoClock(messageItem.createdAt)
        setStatusIcon(isMe, messageItem.status, {
            itemView.chat_flag.setImageDrawable(it)
            itemView.chat_flag.visibility = View.VISIBLE
        }, {
            itemView.chat_flag.visibility = View.GONE
        })

        chatLayout(isMe, isLast)
    }
}
