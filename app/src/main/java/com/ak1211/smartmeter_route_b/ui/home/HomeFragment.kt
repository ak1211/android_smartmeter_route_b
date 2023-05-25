package com.ak1211.smartmeter_route_b.ui.home

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import arrow.core.None
import arrow.core.Some
import com.ak1211.smartmeter_route_b.R
import com.ak1211.smartmeter_route_b.databinding.FragmentHomeBinding
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class HomeFragment : Fragment() {
    private val TAG: String = "HomeFragment"
    private val viewModel by viewModels<HomeViewModel> { HomeViewModel.Factory }

    //
    private val menuProvider: MenuProvider = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) =
            menuInflater.inflate(R.menu.menu_home, menu)

        override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
            R.id.navigation_app_preferences -> {
                findNavController().navigate(R.id.action_navigation_home_to_navigation_app_preferences)
                true
            }

            R.id.navigation_licenses -> {
                findNavController().navigate(R.id.action_navigation_home_to_navigation_licences)
                true
            }

            else -> false
        }
    }

    private var _binding: FragmentHomeBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        // menu
        requireActivity().let { menuHost: MenuHost ->
            menuHost.addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)
        }
        //
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Log.v(TAG, "onViewCreated")
        // floating action buttonハンドラ
        binding.fab.setOnClickListener { viewModel.toggleUiSteateFabOpenOrClose() }
        // 瞬時電力のfloating action buttonハンドラ
        binding.fabInstantWatt.setOnClickListener {
            val payload = listOf<Int>(
                0x10, 0x81,         // EHD
                0x00, 0x00,         // TID
                0x05, 0xFF, 0x01,   // SEOJ
                0x02, 0x88, 0x01,   // DEOJ
                0x62,               //ESV
                0x01,               // OPC
                0xE7,               // 瞬時電力計測値要求
                0x00
            ).map { it.toByte() }.toByteArray()
            viewModel.sendSksendto(payload)
                .onLeft { viewModel.updateUiSteateSnackbarMessage(Some(it.message ?: "error")) }
        }
        // SKVERのfloating action buttonハンドラ
        binding.fabSkver.setOnClickListener {
            viewModel.sendCommand("SKVER")
                .onLeft { viewModel.updateUiSteateSnackbarMessage(Some(it.message ?: "error")) }
        }
        // SKINFOのfloating action buttonハンドラ
        binding.fabSkinfo.setOnClickListener {
            viewModel.sendCommand("SKINFO")
                .onLeft { viewModel.updateUiSteateSnackbarMessage(Some(it.message ?: "error")) }
        }
        //
        binding.viewPagerTwo.adapter = ViewPagerTwoAdapter()
        TabLayoutMediator(binding.tabLayout, binding.viewPagerTwo) { tab, position ->
            tab.text = when (position) {
                0 -> "DASHBOARD"
                1 -> "TERMINAL"
                else -> throw (IllegalStateException())
            }
        }.attach()
        // UiState
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiStateFlow.collect { uiState ->
                    // Snackbarの表示
                    uiState.snackbarMessage.map { message ->
                        Snackbar.make(view, message, Snackbar.LENGTH_LONG).show()
                    }
                    viewModel.updateUiSteateSnackbarMessage(None)
                    //
                    // FABの表示
                    when (uiState.isFloatingActionButtonOpend) {
                        true -> {
                            binding.fab.setImageResource(R.drawable.baseline_keyboard_arrow_down_24)
                            View.VISIBLE
                        }

                        false -> {
                            binding.fab.setImageResource(R.drawable.baseline_keyboard_arrow_up_24)
                            View.INVISIBLE
                        }
                    }.let { v ->
                        binding.fabSkver.visibility = v
                        binding.fabSkinfo.visibility = v
                        binding.fabInstantWatt.visibility = v
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        (requireActivity() as MenuHost).removeMenuProvider(menuProvider)
        super.onDestroyView()
        _binding = null
    }

    private inner class ViewPagerTwoAdapter : FragmentStateAdapter(this@HomeFragment) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> FirstFragment()
            1 -> SecondFragment()
            else -> throw (IllegalStateException())
        }
    }
}