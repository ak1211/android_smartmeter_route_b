package com.ak1211.smartmeter_route_b.ui.home

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
import arrow.core.Some
import arrow.core.getOrElse
import com.ak1211.smartmeter_route_b.data.Incoming
import com.ak1211.smartmeter_route_b.databinding.FragmentFirstBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
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
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        //
//        binding.buttonFirst.setOnClickListener { findNavController().navigate(R.id.action_navigation_home_to_secondFragment) }
        // 閉じるボタンのハンドラー
        binding.buttonCloseport.setOnClickListener(viewModel.handleOnClickButtonClose)
        // 開くボタンのハンドラー
        binding.buttonOpenport.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                //
                val onSuccess: (ReceiveChannel<Incoming>) -> Unit = {
                    viewModel.updateUiSteateSnackbarMessage(Some("開きました"))
                }
                //
                val onFailure: (Throwable) -> Unit = { ex ->
                    val msg: String = ex.message ?: ex.toString()
                    viewModel.updateUiSteateSnackbarMessage(Some(msg))
                }
                //
                // 最初はCloseボタンを選択中にする
                binding.toggleGroup.check(binding.buttonCloseport.id)
                viewModel.viewModelScope.launch(Dispatchers.Default) {
                    viewModel.buttonOpenOnClick().fold(onFailure, onSuccess)
                }
            }
        }
        //
        viewModel.apply {
            // UiStateの更新
            viewModelScope.launch(Dispatchers.Main) {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    isConnected.collect { updateUiSteateIsConnected(it) }
                }
            }
            viewModelScope.launch(Dispatchers.Main) {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    getUsbDeviceName().collect { optName ->
                        updateUiSteateUsbDeviceName(optName.getOrElse { "NOTHING" })
                    }
                }
            }
            viewModelScope.launch(Dispatchers.Main) {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    uiStateFlow.collect { uiState ->
                        // 適切なトグルボタンを選択する
                        val checkedId = when (uiState.isConnected) {
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