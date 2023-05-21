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
import arrow.core.flatMap
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
        //
        binding.fab.setOnClickListener {
            lifecycleScope.launch {
                viewModel.sendCommand("SKVER")
                    .flatMap {
                        viewModel.sendCommand("SKSREG S1")
                    }.flatMap {
                        viewModel.sendCommand("SKINFO")
                    }.onLeft {
                        viewModel.updateUiSteateSnackbarMessage(Some(it.message ?: "error"))
                    }
            }
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
        // Snackbarの表示
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiStateFlow.collect { uiState ->
                    // Snackbarの表示
                    uiState.snackbarMessage.map { message ->
                        Snackbar.make(view, message, Snackbar.LENGTH_LONG).show()
                    }
                    viewModel.updateUiSteateSnackbarMessage(None)
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