package com.ak1211.smartmeter_route_b

import android.app.Application
import android.hardware.usb.UsbManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.ak1211.smartmeter_route_b.data.AppPreferencesRepository
import com.ak1211.smartmeter_route_b.data.ProbedUsbSerialDriversListRepository
import com.ak1211.smartmeter_route_b.data.UsbSerialPortDataSource
import com.ak1211.smartmeter_route_b.data.SmartWhmRouteBRepository


class MyApplication : Application() {
    private val preferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

    //
    private lateinit var _appPreferencesRepository: AppPreferencesRepository
    val appPreferencesRepository get() = _appPreferencesRepository

    //
    private lateinit var _usbSerialPortDataSource: UsbSerialPortDataSource

    //
    private lateinit var _SmartWhmRouteBRepository: SmartWhmRouteBRepository
    val smartWhmRouteBRepository get() = _SmartWhmRouteBRepository

    //
    private lateinit var _probedUsbSerialDriversListRepository: ProbedUsbSerialDriversListRepository
    val probedUsbSerialDriversListRepository get() = _probedUsbSerialDriversListRepository

    override fun onCreate() {
        super.onCreate()
        _appPreferencesRepository = AppPreferencesRepository(preferencesDataStore)
        //
        _usbSerialPortDataSource =
            UsbSerialPortDataSource(getSystemService(USB_SERVICE) as UsbManager)
        //
        _SmartWhmRouteBRepository = SmartWhmRouteBRepository(_usbSerialPortDataSource)
        //
        _probedUsbSerialDriversListRepository = ProbedUsbSerialDriversListRepository(this)
    }
}