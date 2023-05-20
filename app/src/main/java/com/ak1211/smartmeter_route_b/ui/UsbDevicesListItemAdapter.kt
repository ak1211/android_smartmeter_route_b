package com.ak1211.smartmeter_route_b.ui

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.getOrElse
import com.ak1211.smartmeter_route_b.databinding.UsbDevicesListItemBinding


/**
 * USBデバイスリストアイテムアダプタ
 */
class UsbDevicesListItemAdapter(private val onClickHandler: (UsbDevicesListItem) -> Unit) :
    ListAdapter<UsbDevicesListItem, UsbDevicesListItemAdapter.DeviceListItemViewHolder>(
        object : DiffUtil.ItemCallback<UsbDevicesListItem>() {
            override fun areItemsTheSame(
                oldItem: UsbDevicesListItem, newItem: UsbDevicesListItem
            ): Boolean {
                return oldItem == newItem
            }

            override fun areContentsTheSame(
                oldItem: UsbDevicesListItem, newItem: UsbDevicesListItem
            ): Boolean {
                return oldItem == newItem
            }
        }
    ) {
    private var checkedDeviceName: Option<String> = None

    fun setCheckedDeviceName(name: Option<String>) {
        checkedDeviceName = name
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceListItemViewHolder {
        val view = UsbDevicesListItemBinding
            .inflate(LayoutInflater.from(parent.context), parent, false)
        return DeviceListItemViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceListItemViewHolder, position: Int) {
        val item: UsbDevicesListItem = getItem(position)
        holder.bind(item)
    }

    /**
     * リストアイテムのビュー
     */
    inner class DeviceListItemViewHolder(val binding: UsbDevicesListItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: UsbDevicesListItem) {
            binding.usbDevicesListItemDeviceName.text = item.deviceName
            binding.usbDevicesListItemProductName.text = item.productName.getOrElse { "" }
            binding.usbDevicesListItemRadioButton.isChecked =
                Some(item.deviceName) == checkedDeviceName

            val onclick = { _: View ->
                setCheckedDeviceName(Some(item.deviceName))
                onClickHandler(item)
            }
            binding.usbDevicesListItemRadioButton.setOnClickListener(onclick)
            binding.root.setOnClickListener(onclick)
        }
    }
}
