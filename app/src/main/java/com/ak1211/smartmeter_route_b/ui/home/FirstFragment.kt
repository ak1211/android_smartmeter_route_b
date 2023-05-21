package com.ak1211.smartmeter_route_b.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import arrow.core.Some
import arrow.core.getOrElse
import com.ak1211.smartmeter_route_b.databinding.FragmentFirstBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class FirstFragment : Fragment() {
    val TAG: String = "FirstFragment"
    private val viewModel by viewModels<HomeViewModel>(
        { requireParentFragment() },
        null,
        { HomeViewModel.Factory })
    private var _binding: FragmentFirstBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater).apply {
            viewmodel = viewModel
            lifecycleOwner = viewLifecycleOwner
        }
        //
        // 開くボタンのハンドラー
        binding.buttonOpenport.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
                viewModel.handleOnClickButtonOpen()
            }
        }
        // 閉じるボタンのハンドラー
        binding.buttonCloseport.setOnClickListener { viewModel.handleOnClickButtonClose() }
        // SKVERボタンのハンドラ
        binding.buttonSkver.setOnClickListener {
            viewModel.sendCommand("SKVER")
                .onLeft { viewModel.updateUiSteateSnackbarMessage(Some(it.message ?: "error")) }
        }
        // SKSREG_S1ボタンのハンドラ
        binding.buttonSksregS1.setOnClickListener {
            viewModel.sendCommand("SKSREG S1")
                .onLeft { viewModel.updateUiSteateSnackbarMessage(Some(it.message ?: "error")) }
        }
        // SKINFOボタンのハンドラ
        binding.buttonSkinfo.setOnClickListener {
            viewModel.sendCommand("SKINFO")
                .onLeft { viewModel.updateUiSteateSnackbarMessage(Some(it.message ?: "error")) }
        }

        //
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        //
        val dispatcher = Dispatchers.Default
        viewModel.apply {
            // UiStateの更新
            viewLifecycleOwner.lifecycleScope.launch(dispatcher) {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    isConnectedPana.collect { updateUiSteateIsConnectedPana(it) }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch(dispatcher) {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    appPreferencesFlow.collect { appPreferences ->
                        val optName = appPreferences.useUsbSerialDeviceName
                        updateUiSteateUsbDeviceName(optName.getOrElse { "USBデバイスが選択されていません" })
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch(dispatcher) {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    uiStateFlow.collect { uiState ->
                        // 適切なトグルボタンを選択する
                        val checkedId = when (uiState.isConnectedPana) {
                            true -> binding.buttonOpenport.id
                            false -> binding.buttonCloseport.id
                        }
                        binding.toggleGroup.check(checkedId)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}