package com.ak1211.smartmeter_route_b.ui.app_preferences

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.firstOrNone
import arrow.core.toOption
import com.ak1211.smartmeter_route_b.databinding.FragmentAppPreferencesBinding
import com.ak1211.smartmeter_route_b.ui.UsbDevicesListHeaderAdapter
import com.ak1211.smartmeter_route_b.ui.UsbDevicesListItem
import com.ak1211.smartmeter_route_b.ui.UsbDevicesListItemAdapter
import com.google.android.material.snackbar.Snackbar
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class AppPreferencesFragment : Fragment() {
    private val TAG = "AppPreferenceFragment"
    private val viewModel: AppPreferencesViewModel by viewModels() { AppPreferencesViewModel.Factory }
    private val usbDevicesListItemAdapter: UsbDevicesListItemAdapter by lazy {
        UsbDevicesListItemAdapter(::handleOnClickListItem)
    }
    private val usbDevicesListHeaderAdapter: UsbDevicesListHeaderAdapter by lazy { UsbDevicesListHeaderAdapter() }

    private var _binding: FragmentAppPreferencesBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppPreferencesBinding.inflate(inflater, container, false).apply {
            viewmodel = viewModel
            lifecycleOwner = viewLifecycleOwner
            // BルートIDのイベントリスナを登録する
            whmRouteBId.editText?.let { editText ->
                editText.addTextChangedListener(viewModel.whmRouteBSettingsWatcher(editText) {
                    viewModel.updateWhmRouteBId(it)
                })
            }
            // USBデバイスリスト
            usbDevicesList.apply {
                adapter = ConcatAdapter(usbDevicesListHeaderAdapter, usbDevicesListItemAdapter)
                layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
            }
            // アクティブスキャンボタン
            activeScanButton.setOnClickListener {
                lifecycleScope.launch(Dispatchers.Default) {
                    viewModel.handleOnClickActiveScanButton(requireContext())
                        .await()
                        .fold(
                            ::exceptionToSnackbar,
                            { viewModel.updateUiSteateSnackbarMessage(Some(it)) })
                }
            }
            // 消去ボタン
            eraseActiveScanButton.setOnClickListener { viewModel.handleOnClickEraseActiveScanButton() }
            // 登録ボタン
            registerButton.setOnClickListener {
                viewModel.handleOnClickRegisterButton()
                    .fold(::exceptionToSnackbar, { findNavController().navigateUp() })
            }
        }

        return binding.root
    }

    private fun exceptionToSnackbar(ex: Throwable) {
        val msg: String = ex.message ?: ex.toString()
        viewModel.updateUiSteateSnackbarMessage(Some(msg))
    }

    // リストアイテムがクリックされたときに呼ばれるハンドラ
    fun handleOnClickListItem(clickedItem: UsbDevicesListItem) =
        viewModel.updateUseUsbSerialDeviceName(Some(clickedItem.deviceName))

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        Log.v(TAG, "onViewCreated")
        super.onViewCreated(view, savedInstanceState)
        // 設定情報に変更があった
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.getAppPreferences().collect { appPreferences ->
                    val currentUiState = viewModel.uiStateFlow.value
                    val newUiState = currentUiState.copy(appPref = appPreferences)
                    viewModel.updateUiState(newUiState)
                }
            }
        }
        // 接続されているUSBデバイスリストに変更があった
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.getProbedUsbSerialDriversList()
                    .collect { viewModel.updateProbedUsbSerialDriversList(it) }
            }
        }
        // リストを引き下げて更新するハンドラ
        binding.swipeRefreshLayout.setOnRefreshListener {
            viewModel.viewModelScope.launch(Dispatchers.IO) {
                viewModel.probeAllUsbSerialDrivers().await()
                binding.swipeRefreshLayout.isRefreshing = false
            }
        }
        // UiStateの更新
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiStateFlow.collect { uiState ->
                    // Update UI elements
                    val probedDriversList = uiState.probedUsbSerialDriversList
                    // 選択されているドライバー
                    val selectedSerialDriver: Option<UsbSerialDriver> =
                        probedDriversList
                            .filter { Some(it.device.deviceName) == uiState.appPref.useUsbSerialDeviceName }
                            .firstOrNone()
                    //
                    val useUsbSerialDeviceName =
                        selectedSerialDriver.map { it.device.deviceName }
                    viewModel.updateUseUsbSerialDeviceName(useUsbSerialDeviceName)
                    usbDevicesListItemAdapter.setCheckedDeviceName(useUsbSerialDeviceName)
                    //
                    usbDevicesListHeaderAdapter.updateItemsCounter(probedDriversList.size)
                    usbDevicesListItemAdapter.submitList(probedDriversList.map {
                        UsbDevicesListItem(
                            it.device.deviceName, it.device.productName.toOption()
                        )
                    })
                    // Snackbarの表示
                    uiState.snackbarMessage.map {
                        Snackbar.make(view, it, Snackbar.LENGTH_LONG).show()
                        viewModel.updateUiSteateSnackbarMessage(None)
                    }
                    // プログレスバー
                    uiState.progress
                        .fold(
                            { binding.progressBar.visibility = View.GONE },
                            { progress ->
                                binding.progressBar.visibility = View.VISIBLE
                                binding.progressBar.setProgress(progress, true)
                            }
                        )

                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
