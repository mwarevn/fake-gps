package io.github.mwarevn.fakegps.update

import android.content.Context
import android.os.Parcelable
import io.github.mwarevn.fakegps.BuildConfig
import io.github.mwarevn.fakegps.utils.PrefManager
import kotlinx.parcelize.Parcelize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject


class UpdateChecker @Inject constructor(private val apiResponse : GitHubService) {


    fun getLatestRelease() = callbackFlow {
        withContext(Dispatchers.IO){
            getReleaseList()?.let { gitHubReleaseResponse ->
                val currentTag = gitHubReleaseResponse.tagName
                val latestVersion = currentTag?.replace("v", "") ?: ""
                val currentVersion = BuildConfig.TAG_NAME

                // Check if update is enabled and version is different
                if (currentTag != null && latestVersion != currentVersion && !PrefManager.isUpdateDisabled) {
                    //New update available!
                    val asset =
                        gitHubReleaseResponse.assets?.firstOrNull { it.name?.endsWith(".apk") == true }
                    val releaseUrl =
                        asset?.browserDownloadUrl?.replace("/download/", "/tag/")?.apply {
                            substring(0, lastIndexOf("/"))
                        }
                    val name = gitHubReleaseResponse.name ?: "Bản cập nhật mới"
                    val body = gitHubReleaseResponse.body ?: "Vui lòng cập nhật để tiếp tục sử dụng ứng dụng."
                    val publishedAt = gitHubReleaseResponse.publishedAt ?: ""
                    
                    this@callbackFlow.trySend(
                        Update(
                            name,
                            body,
                            publishedAt,
                            asset?.browserDownloadUrl
                                ?: "https://github.com/mwarevn/fake-gps/releases",
                            asset?.name ?: "app-arm64-v8a-release.apk",
                            releaseUrl ?: "https://github.com/mwarevn/fake-gps/releases"
                        )
                    ).isSuccess
                } else {
                    this@callbackFlow.trySend(null).isSuccess
                }
            } ?: run {
                this@callbackFlow.trySend(null).isSuccess
            }
        }
        awaitClose {  }
    }


    private fun getReleaseList(): GitHubRelease? {

        runCatching {
            apiResponse.getReleases().execute().body()
        }.onSuccess {
            return it
        }.onFailure {
            return null
        }
        return null
    }

    fun clearCachedDownloads(context: Context){
        File(context.externalCacheDir, "updates").deleteRecursively()
    }

    @Parcelize
    data class Update(val name: String, val changelog: String, val timestamp: String, val assetUrl: String, val assetName: String, val releaseUrl: String):
        Parcelable
}
