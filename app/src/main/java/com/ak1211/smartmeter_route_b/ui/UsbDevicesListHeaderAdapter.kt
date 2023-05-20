package com.ak1211.smartmeter_route_b.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ak1211.smartmeter_route_b.databinding.UsbDevicesListHeaderBinding

/**
 * USBデバイスリストヘッダアダプタ
 */
class UsbDevicesListHeaderAdapter :
    RecyclerView.Adapter<UsbDevicesListHeaderAdapter.HeaderViewHolder>() {
    private var listOfItemsCounter: Int = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        val view =
            UsbDevicesListHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HeaderViewHolder(view)
    }

    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        holder.bind(listOfItemsCounter)
    }

    override fun getItemCount(): Int = 1 // ヘッダは必ず1

    fun updateItemsCounter(counter: Int) {
        listOfItemsCounter = counter
        notifyDataSetChanged()
    }

    /**
     * ヘッダーのビュー
     */
    inner class HeaderViewHolder(private val binding: UsbDevicesListHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(count: Int) {
            binding.usbDevicesListHeaderNumOfItemsText.text = count.toString()
        }
    }
}
