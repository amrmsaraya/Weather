package com.github.amrmsaraya.weather.presenter.ui

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.createDataStore
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import com.github.amrmsaraya.weather.R
import com.github.amrmsaraya.weather.databinding.ActivityMainBinding
import com.github.amrmsaraya.weather.presenter.viewModel.SharedViewModel
import kotlinx.coroutines.flow.first

class MainActivity : AppCompatActivity() {
    private lateinit var toggle: ActionBarDrawerToggle
    private lateinit var navController: NavController
    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedViewModel: SharedViewModel
    private lateinit var dataStore: DataStore<Preferences>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set background colors for status bar and navigation bar
        window.statusBarColor = Color.parseColor("#FF212121")
        window.navigationBarColor = Color.parseColor("#FF212121")

        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        dataStore = applicationContext.createDataStore("settings")

        lifecycleScope.launchWhenCreated {
            getLocationProvider()
        }

        // Set Custom ActionBar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.displayOptions = ActionBar.DISPLAY_SHOW_CUSTOM
        supportActionBar?.setBackgroundDrawable(getDrawable(R.drawable.gray_square))

        navController = findNavController(R.id.fragment)
        toggle = ActionBarDrawerToggle(this, binding.drawerLayout, R.string.open, R.string.close)
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        sharedViewModel = ViewModelProvider(this).get(SharedViewModel::class.java)

        sharedViewModel.actionBarTitle.observe(this, Observer {
            binding.tvTitle.text = it
        })

        sharedViewModel.currentFragment.observe(this, Observer {
            when (it) {
                "Home" -> binding.navView.setCheckedItem(R.id.navHome)
                "Favorites" -> binding.navView.setCheckedItem(R.id.navFavorites)
                "Alerts" -> binding.navView.setCheckedItem(R.id.navAlerts)
                "Settings" -> binding.navView.setCheckedItem(R.id.navSettings)
            }
        })

        sharedViewModel.actionBarVisibility.observe(this, Observer {
            when (it) {
                "Show" -> supportActionBar?.show()
                else -> supportActionBar?.hide()
            }
        })

        sharedViewModel.weatherAnimation.observe(this, Observer {
            binding.wvWeatherView.apply {
                setWeatherData(it.type)
                emissionRate = it.emissionRate
                speed = it.speed
                angle = it.angel
                fadeOutPercent = it.fadeOutPercent
            }
        })

        binding.navView.setNavigationItemSelectedListener {
            binding.drawerLayout.closeDrawers()
            when (it.itemId) {
                R.id.navHome -> navController.navigate(R.id.homeFragment)
                R.id.navFavorites -> navController.navigate(R.id.favoritesFragment)
                R.id.navAlerts -> navController.navigate(R.id.alertsFragment)
                R.id.navSettings -> navController.navigate(R.id.settingsFragment)
            }
            true
        }
    }

    private suspend fun saveDataStore(key: String, value: String) {
        val dataStoreKey = stringPreferencesKey(key)
        dataStore.edit { settings ->
            settings[dataStoreKey] = value
        }
    }

    private suspend fun readDataStore(key: String): String? {
        val dataStoreKey = stringPreferencesKey(key)
        val preferences = dataStore.data.first()
        return preferences[dataStoreKey]

    }

    private suspend fun getLocationProvider() {
        binding.constrainLayout.visibility = View.GONE
        if (readDataStore("location") == null) {
            var locationProvider = "GPS"
            val locationDialog =
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Location provider")
                    .setIcon(R.drawable.location)
                    .setSingleChoiceItems(arrayOf("GPS", "Map"), 0) { _, which ->
                        when (which) {
                            0 -> locationProvider = "GPS"
                            1 -> locationProvider = "Map"
                        }
                    }
                    .setPositiveButton("OK") { _, _ ->
                        binding.constrainLayout.visibility = View.VISIBLE
                        if (locationProvider == "Map") {
                            navController.navigate(R.id.mapsFragment)
                        }
                        lifecycleScope.launchWhenCreated {
                            saveDataStore("location", locationProvider)
                            saveDataStore("language", "English")
                            saveDataStore("temperature", "Celsius")
                            saveDataStore("windSpeed", "Meter / Sec")
                            Log.i("myTag", "Default Settings have been created!")
                        }
                    }
                    .setCancelable(false)
                    .create()
            locationDialog.show()
        } else {
            binding.constrainLayout.visibility = View.VISIBLE
        }
    }

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(binding.navView)) {
            binding.drawerLayout.closeDrawers()
        } else {
            super.onBackPressed()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (toggle.onOptionsItemSelected(item)) {
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
