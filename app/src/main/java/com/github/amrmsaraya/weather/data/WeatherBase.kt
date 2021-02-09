package com.github.amrmsaraya.weather.data

import com.google.gson.annotations.SerializedName

data class WeatherBase(

	@SerializedName("lat") val lat: Double,
	@SerializedName("lon") val lon: Double,
	@SerializedName("timezone") val timezone: String,
	@SerializedName("timezone_offset") val timezone_offset: Int,
	@SerializedName("current") val current: Current,
	@SerializedName("hourly") val hourly: List<Hourly>,
	@SerializedName("daily") val daily: List<Daily>
)
