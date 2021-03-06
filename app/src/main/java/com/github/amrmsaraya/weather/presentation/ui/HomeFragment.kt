package com.github.amrmsaraya.weather.presentation.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.databinding.DataBindingUtil
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.createDataStore
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.github.amrmsaraya.weather.R
import com.github.amrmsaraya.weather.data.local.WeatherDatabase
import com.github.amrmsaraya.weather.data.models.Location
import com.github.amrmsaraya.weather.data.models.WeatherAnimation
import com.github.amrmsaraya.weather.data.models.WeatherResponse
import com.github.amrmsaraya.weather.databinding.FragmentHomeBinding
import com.github.amrmsaraya.weather.presentation.adapters.DailyAdapter
import com.github.amrmsaraya.weather.presentation.adapters.HourlyAdapter
import com.github.amrmsaraya.weather.presentation.viewModel.LocationViewModel
import com.github.amrmsaraya.weather.presentation.viewModel.SharedViewModel
import com.github.amrmsaraya.weather.presentation.viewModel.WeatherViewModel
import com.github.amrmsaraya.weather.repositories.LocationRepo
import com.github.amrmsaraya.weather.repositories.WeatherRepo
import com.github.amrmsaraya.weather.utils.LocationViewModelFactory
import com.github.amrmsaraya.weather.utils.SharedViewModelFactory
import com.github.amrmsaraya.weather.utils.WeatherViewModelFactory
import com.github.amrmsaraya.weather.workers.UpdateWeatherWorker
import com.github.matteobattilana.weather.PrecipType
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class HomeFragment : Fragment() {
    private lateinit var binding: FragmentHomeBinding
    private lateinit var weatherViewModel: WeatherViewModel
    private lateinit var locationViewModel: LocationViewModel
    private lateinit var sharedViewModel: SharedViewModel
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var geocoder: Geocoder
    private var addresses = mutableListOf<Address>()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val locationPermissionCode = 2
    private var lon = 0.0
    private var lat = 0.0
    private var city = ""

    @SuppressLint("ResourceType")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        binding = DataBindingUtil.inflate(layoutInflater, R.layout.fragment_home, container, false)
        dataStore = requireContext().createDataStore("settings")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        geocoder = Geocoder(context, Locale.getDefault())

        // Dao
        val weatherDao = WeatherDatabase.getInstance(requireActivity().application).weatherDao()
        val locationDao = WeatherDatabase.getInstance(requireActivity().application).locationDao()

        // Repo
        val weatherRepo = WeatherRepo(requireContext(), weatherDao)
        val locationRepo = LocationRepo(locationDao)

        // Factory
        val weatherFactory = WeatherViewModelFactory(weatherRepo)
        val locationFactory = LocationViewModelFactory(locationRepo)
        val sharedFactory = SharedViewModelFactory(requireContext())

        // ViewModels
        weatherViewModel =
            ViewModelProvider(requireActivity(), weatherFactory).get(WeatherViewModel::class.java)
        locationViewModel =
            ViewModelProvider(requireActivity(), locationFactory).get(LocationViewModel::class.java)
        sharedViewModel =
            ViewModelProvider(requireActivity(), sharedFactory).get(SharedViewModel::class.java)

        sharedViewModel.setCurrentFragment("Home")
        sharedViewModel.setActionBarTitle(getString(R.string.home))
        sharedViewModel.setActionBarVisibility(true)

        lifecycleScope.launchWhenStarted {
            if (sharedViewModel.readDataStore("location").isNullOrEmpty()) {
                locationViewModel.insert(
                    Location(
                        roundDouble(0.0),
                        roundDouble(0.0),
                        getString(R.string.unknown),
                        1
                    )
                )
                delay(1500)
                getCachedLocation()

            } else {
                getCachedLocation()
            }
        }

        lifecycleScope.launchWhenStarted {
            delay(1000)
            if (sharedViewModel.readDataStore("location") == "GPS") {
                getLocationFromGPS()
            }
        }

        binding.btnAllowPermission.setOnClickListener {
            getLocationFromGPS()
        }

        lifecycleScope.launchWhenStarted {
            if (sharedViewModel.readDataStore("isWorkerEnqueued").isNullOrEmpty()) {
                while (sharedViewModel.readDataStore("isWorkerEnqueued").isNullOrEmpty()) {
                    delay(1000)
                }
                when (sharedViewModel.readDataStore("isWorkerEnqueued")) {
                    "false" -> {
                        setPeriodicWorkRequest()
                        sharedViewModel.saveDataStore("isWorkerEnqueued", "true")
                    }
                    "true" -> Unit
                    else -> Unit
                }
            }
        }

        // SwipeRefresh
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#FF313131"))
        binding.swipeRefresh.setColorSchemeColors(Color.parseColor("#FFFF00e4"))
        binding.swipeRefresh.setOnRefreshListener {
            lifecycleScope.launchWhenStarted {
                if (sharedViewModel.locationType.value == "GPS") {
                    getLocationFromGPS()
                }
                if (lat != 0.0 && lon != 0.0) {
                    weatherViewModel.getLiveWeather(lat, lon, lang = sharedViewModel.langUnit.value)
                }
            }
        }

        binding.tvDate.text =
            SimpleDateFormat("E, dd MMM", Locale.getDefault()).format(System.currentTimeMillis())


        // Get Retrofit Response
        lifecycleScope.launchWhenStarted {
            weatherViewModel.weatherResponse.collect {
                when (it) {
                    is WeatherRepo.ResponseState.Success -> {
                        binding.swipeRefresh.isRefreshing = false
                        displayWeather(it.weatherResponse)
                        // Delete old current weather data
                        weatherViewModel.deleteCurrent()
                        val response = it.weatherResponse
                        response.isCurrent = true
                        // Insert the new current weather data
                        weatherViewModel.insert(response)
                    }
                    is WeatherRepo.ResponseState.Error -> {
                        if (lat != 0.0 && lon != 0.0) {
                            val weatherResponse =
                                weatherViewModel.getCachedLocationWeather(lat, lon)
                            if (weatherResponse != null) {
                                displayWeather(weatherResponse)
                            }
                        }
                        binding.swipeRefresh.isRefreshing = false
                        Snackbar.make(
                            binding.root,
                            getString(R.string.no_internet_connection),
                            Snackbar.LENGTH_SHORT
                        ).show()
                    }
                    else -> Unit
                }
                weatherViewModel.weatherResponse.value = WeatherRepo.ResponseState.Empty
            }
        }

        val layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvHourly.layoutManager = layoutManager
        binding.rvHourly.adapter = HourlyAdapter(requireContext(), sharedViewModel)

        binding.rvDaily.layoutManager = LinearLayoutManager(context)
        binding.rvDaily.adapter = DailyAdapter(requireContext(), sharedViewModel)


        return binding.root
    }


    // Display weather Data
    @SuppressLint("UseCompatLoadingForDrawables")
    private fun displayWeather(weatherResponse: WeatherResponse) {
        val current = weatherResponse.current
        val animationType: PrecipType
        var emissionRate = 100f
        var temp = current.temp

        when (sharedViewModel.tempUnit.value) {
            "Celsius" -> {
                binding.tvTempUnit.text = "°C"
            }
            "Kelvin" -> {
                temp += 273.15
                binding.tvTempUnit.text = "°K"
            }
            "Fahrenheit" -> {
                temp = (temp * 1.8) + 32
                binding.tvTempUnit.text = "°F"
            }
        }

        when (sharedViewModel.windUnit.value) {
            "Meter / Sec" -> binding.tvWindSpeed.text =
                "${current.wind_speed} ${getString(R.string.m_s)}"
            "Mile / Hour"
            -> binding.tvWindSpeed.text =
                "${"%.2f".format(current.wind_speed * 2.236936)} ${getString(R.string.mph)}"
        }

        binding.tvTemp.text = temp.roundToInt().toString()
        binding.tvDescription.text =
            current.weather[0].description.capitalize(Locale.ROOT)
        binding.tvPressure.text = "${current.pressure} ${getString(R.string.hpa)}"
        binding.tvHumidity.text = "${current.humidity} %"
        binding.tvClouds.text = "${current.clouds} %"
        binding.tvUltraviolet.text = current.uvi.toString()
        binding.tvVisibility.text = "${current.visibility} ${getString(R.string.meter)}"

        // Display icon and animation
        when (current.weather[0].main) {
            "Clear" -> {
                animationType = PrecipType.CLEAR
                if (Calendar.getInstance().timeInMillis / 1000 in current.sunrise until current.sunset) {
                    binding.ivIcon.setImageDrawable(context?.getDrawable(R.drawable.clear_day))
                } else {
                    binding.ivIcon.setImageDrawable(context?.getDrawable(R.drawable.clear_night))
                }
            }
            "Clouds" -> {
                animationType = PrecipType.CLEAR
                if (Calendar.getInstance().timeInMillis / 1000 in current.sunrise until current.sunset) {
                    binding.ivIcon.setImageDrawable(context?.getDrawable(R.drawable.cloudy_day))
                } else {
                    binding.ivIcon.setImageDrawable(context?.getDrawable(R.drawable.cloudy_night))
                }
            }
            "Drizzle" -> {
                animationType = PrecipType.RAIN
                emissionRate = 25f
                if (Calendar.getInstance().timeInMillis / 1000 in current.sunrise until current.sunset) {
                    binding.ivIcon.setImageDrawable(context?.getDrawable(R.drawable.rainy_day))
                } else {
                    binding.ivIcon.setImageDrawable(context?.getDrawable(R.drawable.rainy_night))
                }
            }
            "Rain" -> {
                animationType = PrecipType.RAIN
                emissionRate = 100f
                if (Calendar.getInstance().timeInMillis / 1000 in current.sunrise until current.sunset) {
                    binding.ivIcon.setImageDrawable(context?.getDrawable(R.drawable.rainy_day))
                } else {
                    binding.ivIcon.setImageDrawable(context?.getDrawable(R.drawable.rainy_night))
                }
            }
            "Snow" -> {
                animationType = PrecipType.SNOW
                binding.ivIcon.setImageDrawable(context?.getDrawable(R.drawable.snow))
            }
            "Thunderstorm" -> {
                animationType = PrecipType.RAIN
                emissionRate = 50f
                binding.ivIcon.setImageDrawable(context?.getDrawable(R.drawable.storm))
            }
            else -> {
                animationType = PrecipType.CLEAR
                if (Calendar.getInstance().timeInMillis / 1000 in current.sunrise until current.sunset) {
                    binding.ivIcon.setImageDrawable(context?.getDrawable(R.drawable.foggy_day))
                } else {
                    binding.ivIcon.setImageDrawable(context?.getDrawable(R.drawable.foggy_night))
                }
            }
        }
        sharedViewModel.setWeatherAnimation(WeatherAnimation(animationType, emissionRate))

        val hourlyAdapter = binding.rvHourly.adapter as HourlyAdapter
        val dailyAdapter = binding.rvDaily.adapter as DailyAdapter

        // Send sunrise and sunset time to Adapter
        hourlyAdapter.setSunriseAndSunset(weatherResponse.daily)
        // Add List to Hourly Adapter
        hourlyAdapter.submitList(weatherResponse.hourly.subList(0, 24))
        // Add List to Daily Adapter
        dailyAdapter.submitList(
            weatherResponse.daily.subList(1, weatherResponse.daily.size - 1)
        )
    }


    private suspend fun getCachedLocation() {
        locationViewModel.getLocation(1).collect {
            if (it.lat != 0.0 && it.lon != 0.0) {
                val weatherResponse =
                    weatherViewModel.getCachedLocationWeather(it.lat, it.lon)
                if (weatherResponse != null) {
                    displayWeather(weatherResponse)
                }
                binding.swipeRefresh.isRefreshing = true
                weatherViewModel.getLiveWeather(
                    it.lat,
                    it.lon,
                    lang = sharedViewModel.langUnit.value
                )
            }
            lat = it.lat
            lon = it.lon
            city = it.name
            binding.tvCurrentAddress.text = it.name
            sharedViewModel.setCurrentLatLng(LatLng(it.lat, it.lon))
        }
    }

    private fun roundDouble(double: Double): Double {
        return BigDecimal(double).setScale(4, RoundingMode.HALF_UP).toDouble()
    }

    private fun setPeriodicWorkRequest() {
        val workManager = WorkManager.getInstance(requireContext().applicationContext)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val updateWeatherWorkRequest = PeriodicWorkRequest
            .Builder(UpdateWeatherWorker::class.java, 1, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        workManager.enqueue(updateWeatherWorkRequest)
        Log.i("myTag", "Enqueued Done")
    }

    private fun getLocationFromGPS() {
        if (checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                locationPermissionCode
            )
            return
        }
        fusedLocationClient.getCurrentLocation(
            LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY,
            null
        ).addOnSuccessListener {
            if (it != null) {
                var newCity = ""
                try {
                    addresses = geocoder.getFromLocation(
                        it.latitude,
                        it.longitude,
                        1
                    )
                    newCity = if (addresses[0].locality.isNullOrEmpty()) {
                        addresses[0].adminArea
                    } else {
                        addresses[0].locality
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
                if ((roundDouble(it.latitude) != lat && roundDouble(it.longitude) != lon) || newCity != city) {
                    locationViewModel.insert(
                        Location(
                            roundDouble(it.latitude),
                            roundDouble(it.longitude),
                            newCity,
                            1
                        )
                    )
                }
            } else {
                Snackbar.make(binding.root, "Failed to get Location", Snackbar.LENGTH_SHORT)
                    .show()
            }
        }.addOnFailureListener {
            Snackbar.make(binding.root, "Failed to get location", Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == locationPermissionCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                binding.currentTempLayout.visibility = View.VISIBLE
                binding.detailsLayout.visibility = View.VISIBLE
                binding.noPermissionLayout.visibility = View.GONE
                getLocationFromGPS()
                lifecycleScope.launchWhenStarted {
                    sharedViewModel.setDefaultSettings("GPS")
                }
            } else {
                binding.currentTempLayout.visibility = View.GONE
                binding.detailsLayout.visibility = View.GONE
                binding.noPermissionLayout.visibility = View.VISIBLE
            }
        }
        sharedViewModel.setMainActivityVisibility("true")
    }


}
