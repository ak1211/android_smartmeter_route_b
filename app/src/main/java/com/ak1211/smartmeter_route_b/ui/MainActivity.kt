package com.ak1211.smartmeter_route_b.ui

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.ak1211.smartmeter_route_b.MyApplication
import com.ak1211.smartmeter_route_b.R
import com.ak1211.smartmeter_route_b.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 *
 */
class MainActivity : AppCompatActivity() {
    private val TAG: String = "MainActivity"
    private lateinit var appBarConfiguration: AppBarConfiguration

    private lateinit var binding: ActivityMainBinding

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.v(TAG, "onCreate")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        //
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)
        //
        val myApplication: MyApplication? = application?.let { it as MyApplication }
        myApplication?.apply {
            // バックグラウンドでUSBシリアルドライバーを検出する
            lifecycleScope.launch(Dispatchers.Default) {
                probedUsbSerialDriversListRepository
                    .probeAllUsbSerialDrivers()
                    .await()
            }
            // 設定情報を読み込んでリポジトリに入れる
            lifecycleScope.launch(Dispatchers.Default) {
                appPreferencesRepository.restoreAppPreferences().await()
            }
            // 更新されたら設定情報を保存する
            lifecycleScope.launch(Dispatchers.Default) {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    appPreferencesRepository.appPreferences.collect {
                        runBlocking { appPreferencesRepository.saveAppPreferences(it) }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val myApplication: MyApplication? = application?.let { it as MyApplication }
        myApplication?.apply {
            probedUsbSerialDriversListRepository.registerReceiver(lifecycleScope)
        }
    }

    override fun onStop() {
        val myApplication: MyApplication? = application?.let { it as MyApplication }
        myApplication?.apply {
            probedUsbSerialDriversListRepository.unregisterReceiver()
        }
        super.onStop()
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }
}
