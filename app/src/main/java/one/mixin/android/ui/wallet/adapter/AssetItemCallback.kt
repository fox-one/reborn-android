package one.mixin.android.ui.wallet.adapter

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import one.mixin.android.ui.common.recyclerview.HeaderAdapter
import one.mixin.android.ui.common.recyclerview.ItemTouchCallback

class AssetItemCallback(listener: ItemCallbackListener) :
    ItemTouchCallback(listener) {

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        val swipeFlags = if (viewHolder is HeaderAdapter.NormalHolder) {
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        } else {
            0
        }
        return makeMovementFlags(0, swipeFlags)
    }
}