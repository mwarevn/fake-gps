package io.github.mwarevn.fakegps.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.view.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import dagger.hilt.android.AndroidEntryPoint
import io.github.mwarevn.fakegps.BuildConfig
import io.github.mwarevn.fakegps.R
import io.github.mwarevn.fakegps.adapter.FavListAdapter
import io.github.mwarevn.fakegps.databinding.ActivityMapBinding
import io.github.mwarevn.fakegps.ui.viewmodel.MainViewModel
import io.github.mwarevn.fakegps.utils.JoystickService
import io.github.mwarevn.fakegps.utils.NetworkUtils
import io.github.mwarevn.fakegps.utils.NotificationsChannel
import io.github.mwarevn.fakegps.utils.PrefManager
import io.github.mwarevn.fakegps.utils.ext.*
import kotlinx.coroutines.*
import kotlin.properties.Delegates

@AndroidEntryPoint
abstract class BaseMapActivity: AppCompatActivity() {

    protected var lat by Delegates.notNull<Double>()
    protected var lon by Delegates.notNull<Double>()
    protected val viewModel by viewModels<MainViewModel>()
    protected val binding by lazy { ActivityMapBinding.inflate(layoutInflater) }
    
    private val notificationsChannel by lazy { NotificationsChannel() }
    private val favListAdapter = FavListAdapter()
    private var xposedDialog: AlertDialog? = null
    private var updateDialogInstance: AlertDialog? = null
    private var downloadDialogInstance: AlertDialog? = null
    protected lateinit var fusedLocationClient: FusedLocationProviderClient
    
    protected val PERMISSION_ID = 42

    protected abstract fun getActivityInstance(): BaseMapActivity
    protected abstract fun hasMarker(): Boolean
    protected abstract fun initializeMap()
    protected abstract fun setupButtons()
    protected abstract fun moveMapToNewLocation(moveNewLocation: Boolean, shouldMark: Boolean = false)

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        // Initialize default coordinates (Hanoi)
        lat = 21.0285
        lon = 105.8542
        
        initializeMap()
        observeAppStatus()
        checkAppAvailability()
        checkModuleEnabled()
        checkUpdates()
        setupNavView()
        setupButtons()
        setupDrawer()
        
        if (PrefManager.isJoystickEnabled){
            startService(Intent(this, JoystickService::class.java))
        }
    }

    private fun checkAppAvailability() {
        if (!NetworkUtils.isNetworkAvailable(this)) {
            viewModel.setNoInternetStatus()
        } else {
            viewModel.checkAppStatus()
        }
    }

    private fun observeAppStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.appStatus.collect { status ->
                    when (status) {
                        is MainViewModel.AppStatus.Disallowed -> {
                            showExitDialog("Phiên bản cũ", status.message ?: "Ứng dụng này không còn được hỗ trợ.")
                        }
                        is MainViewModel.AppStatus.NoInternet -> {
                            showExitDialog("Không có internet", "Vui lòng kiểm tra kết nối internet để sử dụng ứng dụng.")
                        }
                        else -> { /* Checking, Allowed, Error handled silently */ }
                    }
                }
            }
        }
    }

    private fun showExitDialog(title: String, message: String) {
        if (isFinishing) return
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Thoát") { _, _ ->
                finishAffinity()
            }
            .show()
    }

    private fun setupDrawer() {
        supportActionBar?.setDisplayShowTitleEnabled(false)
        val mDrawerToggle = object : ActionBarDrawerToggle(
            this,
            binding.container,
            binding.toolbar,
            R.string.drawer_open,
            R.string.drawer_close
        ) {
            override fun onDrawerClosed(view: View) {
                super.onDrawerClosed(view)
                invalidateOptionsMenu()
            }

            override fun onDrawerOpened(drawerView: View) {
                super.onDrawerOpened(drawerView)
                invalidateOptionsMenu()
            }
        }
        binding.container.addDrawerListener(mDrawerToggle)
        mDrawerToggle.syncState()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun setupNavView() {
        binding.container.setOnApplyWindowInsetsListener { _, insets ->
            val systemInsets = WindowInsetsCompat.toWindowInsetsCompat(insets).getInsets(WindowInsetsCompat.Type.systemBars())
            binding.navView.setPadding(0, systemInsets.top, 0, 0)
            insets
        }

        binding.navView.setNavigationItemSelectedListener {
            when(it.itemId){
                R.id.show_fake_icon -> {
                    val switch = it.actionView as? androidx.appcompat.widget.SwitchCompat
                    switch?.isChecked = !(switch?.isChecked ?: PrefManager.isShowFakeIcon)
                    return@setNavigationItemSelectedListener true
                }
                R.id.routing_settings -> RoutingSettingsDialog.show(this)
                R.id.get_favorite -> openFavoriteListDialog()
                R.id.settings -> startActivity(Intent(this,ActivitySettings::class.java))
                R.id.about -> aboutDialog()
            }
            binding.container.closeDrawer(GravityCompat.START)
            true
        }
        
        // Initialize Toggle State
        val showFakeIconItem = binding.navView.menu.findItem(R.id.show_fake_icon)
        val showFakeIconSwitch = showFakeIconItem.actionView as? androidx.appcompat.widget.SwitchCompat
        showFakeIconSwitch?.isChecked = PrefManager.isShowFakeIcon
        showFakeIconSwitch?.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.isShowFakeIcon = isChecked
            onShowFakeIconToggled(isChecked)
        }
    }

    protected open fun onShowFakeIconToggled(show: Boolean) {}

    private fun checkModuleEnabled(){
        viewModel.isXposed.observe(this) { isXposed ->
            xposedDialog?.dismiss()
            if (!isXposed) {
                xposedDialog = MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.error_xposed_module_missing)
                    .setMessage(R.string.error_xposed_module_missing_desc)
                    .setCancelable(true)
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.updateXposedState()
        viewModel.checkForUpdates()
    }

    protected fun aboutDialog(){
        MaterialAlertDialogBuilder(this).apply {
            val view = layoutInflater.inflate(R.layout.about, null)
            view.findViewById<TextView>(R.id.design_about_title).text = getString(R.string.app_name)
            view.findViewById<TextView>(R.id.design_about_version).text = BuildConfig.VERSION_NAME
            view.findViewById<TextView>(R.id.design_about_info).text = getString(R.string.about_info)
            setView(view)
            show()
        }
    }

    protected fun addFavoriteDialog() {
        MaterialAlertDialogBuilder(this).apply {
            val view = layoutInflater.inflate(R.layout.dialog, null)
            val editText = view.findViewById<EditText>(R.id.search_edittxt)
            setTitle(getString(R.string.add_fav_dialog_title))
            setPositiveButton(getString(R.string.dialog_button_add)) { _, _ ->
                val s = editText.text.toString().trim()
                if (s.isEmpty()) {
                    showToast("Vui lòng nhập tên địa điểm")
                } else if (hasMarker()){
                  showToast(getString(R.string.location_not_select))
                }else{
                    viewModel.storeFavorite(s, lat, lon)
                }
            }
            setNegativeButton("Hủy", null)
            setView(view)
            show()
        }
    }

    private fun openFavoriteListDialog() {
        viewModel.doGetUserDetails()
        val view = layoutInflater.inflate(R.layout.fav, null)
        val rcv = view.findViewById<RecyclerView>(R.id.favorites_list)
        rcv.layoutManager = LinearLayoutManager(this)
        rcv.adapter = favListAdapter
        
        val favDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.favorites))
            .setView(view)
            .create()
            
        favListAdapter.onItemClick = {
            lat = it.lat ?: 21.0285
            lon = it.lng ?: 105.8542
            moveMapToNewLocation(moveNewLocation = true, shouldMark = true)
            favDialog.dismiss()
        }
        favListAdapter.onItemDelete = { favorite ->
            MaterialAlertDialogBuilder(this)
                .setTitle("Xóa yêu thích")
                .setMessage("Bạn có chắc chắn muốn xóa địa điểm '${favorite.address}'?")
                .setPositiveButton("Xóa") { _, _ ->
                    viewModel.deleteFavorite(favorite)
                    showToast(" Đã xóa thành công")
                }
                .setNegativeButton("Hủy", null)
                .show()
        }
        
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.allFavList.collect {
                    favListAdapter.submitList(it)
                }
            }
        }
        
        favDialog.show()
    }

    private fun checkUpdates(){
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.update.collect{
                    if (it != null) updateDialog()
                }
            }
        }
    }

    private fun updateDialog(){
        if (updateDialogInstance?.isShowing == true || downloadDialogInstance?.isShowing == true) return
        
        updateDialogInstance = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_available)
            .setMessage(viewModel.getAvailableUpdate()?.changelog)
            .setCancelable(BuildConfig.DEBUG)
            .setPositiveButton(getString(R.string.update_button)) { _, _ ->
                showDownloadProgressDialog()
            }
            .setNegativeButton("Thoát") { _, _ ->
                if (!BuildConfig.DEBUG) finishAffinity()
            }
            .show()
    }

    private fun showDownloadProgressDialog() {
        if (downloadDialogInstance?.isShowing == true) return
        
        val view = layoutInflater.inflate(R.layout.update_dialog, null)
        val progress = view.findViewById<LinearProgressIndicator>(R.id.update_download_progress)
        val cancel = view.findViewById<AppCompatButton>(R.id.update_download_cancel)
        
        downloadDialogInstance = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(BuildConfig.DEBUG)
            .create()
            
        cancel.setOnClickListener {
            viewModel.cancelDownload(getActivityInstance())
            downloadDialogInstance?.dismiss()
            if (!BuildConfig.DEBUG) finishAffinity()
        }
        
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.downloadState.collect {
                    when (it) {
                        is MainViewModel.State.Downloading -> {
                            if (it.progress > 0) {
                                progress.isIndeterminate = false
                                progress.progress = it.progress
                            }
                        }
                        is MainViewModel.State.Done -> {
                            viewModel.openPackageInstaller(getActivityInstance(), it.fileUri)
                            viewModel.clearUpdate()
                            downloadDialogInstance?.dismiss()
                        }
                        is MainViewModel.State.Failed -> {
                            showToast(getString(R.string.bs_update_download_failed))
                            downloadDialogInstance?.dismiss()
                            if (!BuildConfig.DEBUG) finishAffinity()
                        }
                        else -> {}
                    }
                }
            }
        }
        viewModel.getAvailableUpdate()?.let { viewModel.startDownload(getActivityInstance(), it) }
        downloadDialogInstance?.show()
    }

    protected fun showStartNotification(address: String){
        notificationsChannel.showNotification(this){
            it.setSmallIcon(R.drawable.ic_stop)
            it.setContentTitle(getString(R.string.location_set))
            it.setContentText(address)
            it.setAutoCancel(true)
            it.setCategory(Notification.CATEGORY_EVENT)
            it.priority = NotificationCompat.PRIORITY_HIGH
        }
    }

    protected fun cancelNotification(){
        notificationsChannel.cancelAllNotifications(this)
    }

    @SuppressLint("MissingPermission")
    protected fun getLastLocation() {
        if (!checkPermissions()) {
            requestPermissions()
            return
        }

        if (!isLocationEnabled()) {
            showToast("Vui lòng bật vị trí hệ thống")
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                lat = location.latitude
                lon = location.longitude
                moveMapToNewLocation(moveNewLocation = true, shouldMark = false)
            } else {
                requestNewLocationData()
            }
        }.addOnFailureListener {
            requestNewLocationData()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestNewLocationData() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMaxUpdates(1)
            .build()
            
        fusedLocationClient.requestLocationUpdates(locationRequest, object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val lastLocation = locationResult.lastLocation ?: return
                lat = lastLocation.latitude
                lon = lastLocation.longitude
                moveMapToNewLocation(moveNewLocation = true, shouldMark = false)
                fusedLocationClient.removeLocationUpdates(this)
            }
        }, Looper.getMainLooper())
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || 
               locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun checkPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
               ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    protected fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            PERMISSION_ID
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_ID && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            getLastLocation()
        }
    }
}
