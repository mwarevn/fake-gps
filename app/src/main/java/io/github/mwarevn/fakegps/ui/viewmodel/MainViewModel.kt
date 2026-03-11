package io.github.mwarevn.fakegps.ui.viewmodel


import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import io.github.mwarevn.fakegps.BuildConfig
import io.github.mwarevn.fakegps.R
import io.github.mwarevn.fakegps.domain.model.FavoriteLocation
import io.github.mwarevn.fakegps.domain.model.LatLng
import io.github.mwarevn.fakegps.domain.model.VehicleType
import io.github.mwarevn.fakegps.domain.repository.IFavoriteRepository
import io.github.mwarevn.fakegps.domain.routing.IRoutingProvider
import io.github.mwarevn.fakegps.domain.usecase.CalculateRouteUseCase
import io.github.mwarevn.fakegps.network.StatusService
import io.github.mwarevn.fakegps.update.UpdateChecker
import io.github.mwarevn.fakegps.utils.PrefManager
import io.github.mwarevn.fakegps.utils.ext.onIO
import io.github.mwarevn.fakegps.utils.ext.onMain
import io.github.mwarevn.fakegps.utils.ext.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class MainViewModel @Inject constructor(
    private val favoriteRepository: IFavoriteRepository,
    private val prefManger: PrefManager,
    private val checkUpdates: UpdateChecker,
    private val downloadManager: DownloadManager,
    private val statusService: StatusService,
    private val calculateRouteUseCase: CalculateRouteUseCase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // FIX: Change these to dynamic getters so they always return fresh values from PrefManager
    val getLat: Double get() = prefManger.getLat
    val getLng: Double get() = prefManger.getLng
    val isStarted: Boolean get() = prefManger.isStarted
    val mapType: Int get() = prefManger.mapType


    private val _allFavList = MutableStateFlow<List<FavoriteLocation>>(emptyList())
    val allFavList : StateFlow<List<FavoriteLocation>> =  _allFavList
    fun doGetUserDetails(){
        onIO {
            favoriteRepository.getAllFavorites()
                .catch { e ->
                    Timber.tag("Error in getting all save favorite").d(e.message.toString())
                }
                .collectLatest {
                    _allFavList.emit(it)
                }
        }
    }

    fun update(start: Boolean, la: Double, ln: Double, bearing: Float = 0F, speed: Float = 0F)  {
        prefManger.update(start, la, ln, bearing, speed)
    }

    private val _currentRoute = MutableStateFlow<List<LatLng>>(emptyList())
    val currentRoute: StateFlow<List<LatLng>> = _currentRoute.asStateFlow()

    private val _routeError = MutableSharedFlow<String>()
    val routeError = _routeError.asSharedFlow()

    private var routingJob: Job? = null

    fun calculateRoute(start: LatLng, dest: LatLng, vehicle: VehicleType) {
        routingJob?.cancel()
        routingJob = viewModelScope.launch {
            calculateRouteUseCase(start, dest, vehicle)
                .onSuccess { _currentRoute.emit(it) }
                .onFailure { _routeError.emit(it.message ?: "Unknown error") }
        }
    }

    fun clearRoute() {
        viewModelScope.launch { _currentRoute.emit(emptyList()) }
    }

    private val _response = MutableLiveData<Long>()
    val response: LiveData<Long> = _response


    private fun insertNewFavorite(favorite: FavoriteLocation) = onIO {
        _response.postValue(favoriteRepository.addNewFavorite(favorite))

    }

    val isXposed = MutableLiveData<Boolean>(true)
    
    // Method to check if module is active (will be hooked by Xposed)
    fun isModuleActive(): Boolean {
        return false
    }

    fun updateXposedState() {
        onMain {
            isXposed.value = isModuleActive()
        }
    }

    fun deleteFavorite(favorite: FavoriteLocation) = onIO {
        favoriteRepository.deleteFavorite(favorite)
    }


    private val _update = MutableStateFlow<UpdateChecker.Update?>(null)

    fun checkForUpdates() {
        viewModelScope.launch {
            checkUpdates.getLatestRelease().collect {
                if (it == null) {
                    withContext(Dispatchers.IO){
                        checkUpdates.clearCachedDownloads(context)
                    }
                }
                _update.emit(it)
            }
        }
    }

     val update = _update.asStateFlow()

    fun getAvailableUpdate(): UpdateChecker.Update? {
        return _update.value
    }

    fun clearUpdate() {
        viewModelScope.launch {
            _update.emit(null)
        }
    }


    private var requestId: Long? = null
    private var _downloadState = MutableStateFlow<State>(State.Idle)
    private var downloadFile: File? = null
    val downloadState = _downloadState.asStateFlow()

    private val _appStatus = MutableStateFlow<AppStatus>(AppStatus.Checking)
    val appStatus: StateFlow<AppStatus> = _appStatus.asStateFlow()

    sealed class AppStatus {
        object Checking : AppStatus()
        object Allowed : AppStatus()
        data class Disallowed(val message: String?) : AppStatus()
        object NoInternet : AppStatus()
        data class Error(val message: String?) : AppStatus()
    }

    fun checkAppStatus() {
        viewModelScope.launch {
            _appStatus.value = AppStatus.Checking
            try {
                val response = statusService.getStatus()
                if (response.isNotEmpty()) {
                    val status = response[0].status
                    if (status.equals("new", ignoreCase = true)) {
                        _appStatus.value = AppStatus.Allowed
                    } else {
                        _appStatus.value = AppStatus.Allowed
                       // _appStatus.value = AppStatus.Disallowed("App version is old and no longer supported.")
                    }
                } else {
                    _appStatus.value = AppStatus.Error("Empty response from server")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error checking app status")
                _appStatus.value = AppStatus.Error(e.message)
            }
        }
    }
    
    fun setNoInternetStatus() {
        _appStatus.value = AppStatus.NoInternet
    }


    // Got idea from https://github.com/KieronQuinn/DarQ for Check Update
    fun startDownload(context: Context, update: UpdateChecker.Update) {
        if(_downloadState.value !is State.Idle) return

        val downloadFolder = File(context.externalCacheDir, "updates")
        val existingFile = File(downloadFolder, update.assetName)
        
        // Phase 16: Cache Reuse and Integrity Check
        if (existingFile.exists()) {
            val packageInfo = context.packageManager.getPackageArchiveInfo(existingFile.absolutePath, 0)
            if (packageInfo != null) {
                // The APK is cryptographically intact and fully downloaded.
                val outputUri = FileProvider.getUriForFile(
                    context, 
                    BuildConfig.APPLICATION_ID + ".provider", 
                    existingFile
                )
                viewModelScope.launch {
                    _downloadState.emit(State.Done(outputUri))
                }
                return
            } else {
                // The APK is corrupt or partially downloaded
                existingFile.delete()
            }
        }
        
        downloadUpdate(context, update.assetUrl, update.assetName)
    }

    private val downloadStateReceiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            viewModelScope.launch {
                var success = false
                val query = DownloadManager.Query().apply {
                    setFilterById(requestId ?: return@apply)
                }
                val cursor = downloadManager.query(query)
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (cursor.getInt(columnIndex) == DownloadManager.STATUS_SUCCESSFUL) {
                        success = true
                    }
                }
                if (success && downloadFile != null) {
                    val outputUri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".provider", downloadFile!!)
                    _downloadState.emit(State.Done(outputUri))
                } else {
                    _downloadState.emit(State.Failed)
                }
            }
        }
    }

    private val downloadObserver = object: ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            viewModelScope.launch {
                val query = DownloadManager.Query()
                query.setFilterById(requestId ?: return@launch)
                val c: Cursor = downloadManager.query(query)
                var progress = 0.0
                if (c.moveToFirst()) {
                    val sizeIndex: Int =
                        c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val downloadedIndex: Int =
                        c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val size = c.getInt(sizeIndex)
                    val downloaded = c.getInt(downloadedIndex)
                    if (size != -1) progress = downloaded * 100.0 / size
                }
                _downloadState.emit(State.Downloading(progress.roundToInt()))
            }
        }
    }

    private fun downloadUpdate(context: Context, url: String, fileName: String) = viewModelScope.launch {
        val downloadFolder = File(context.externalCacheDir, "updates").apply {
            mkdirs()
        }
        downloadFile = File(downloadFolder, fileName)
        ContextCompat.registerReceiver(
            context,
            downloadStateReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
        context.contentResolver.registerContentObserver(Uri.parse("content://downloads/my_downloads"), true, downloadObserver)
        requestId = DownloadManager.Request(Uri.parse(url)).apply {
            setDescription(context.getString(R.string.download_manager_description))
            setTitle(context.getString(R.string.app_name))
            setDestinationUri(Uri.fromFile(downloadFile!!))
        }.run {
            downloadManager.enqueue(this)
        }
    }

    fun openPackageInstaller(context: Context, uri: Uri){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse(String.format("package:%s", context.packageName))
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
            context.showToast(context.getString(R.string.app_name) + " cần quyền cài đặt ứng dụng. Vui lòng cấp quyền và thử lại.")
            return
        }

        runCatching {
            Intent(Intent.ACTION_VIEW, uri).apply {
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.also {
                context.startActivity(it)
            }
        }.onFailure {
            it.printStackTrace()
            context.showToast(context.getString(R.string.app_update_failed))
        }

    }

    fun cancelDownload(context: Context) {
        viewModelScope.launch {
            requestId?.let {
                downloadManager.remove(it)
            }
            context.unregisterReceiver(downloadStateReceiver)
            context.contentResolver.unregisterContentObserver(downloadObserver)
            _downloadState.emit(State.Idle)
        }
    }

    sealed class State {
        object Idle: State()
        data class Downloading(val progress: Int): State()
        data class Done(val fileUri: Uri): State()
        object Failed: State()
    }



     fun storeFavorite(
        address: String,
        lat: Double,
        lon: Double
    ) = onIO {
        favoriteRepository.addNewFavorite(FavoriteLocation(0, address, lat, lon))
    }
}
