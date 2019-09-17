package com.stevesoltys.backup.ui.storage

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.stevesoltys.backup.R
import com.stevesoltys.backup.settings.Storage
import com.stevesoltys.backup.settings.getStorage
import com.stevesoltys.backup.settings.setStorage
import com.stevesoltys.backup.transport.ConfigurableBackupTransportService
import com.stevesoltys.backup.ui.LiveEvent
import com.stevesoltys.backup.ui.MutableLiveEvent

private val TAG = StorageViewModel::class.java.simpleName

internal abstract class StorageViewModel(private val app: Application) : AndroidViewModel(app), RemovableStorageListener {

    private val mStorageRoots = MutableLiveData<List<StorageRoot>>()
    internal val storageRoots: LiveData<List<StorageRoot>> get() = mStorageRoots

    private val mLocationSet = MutableLiveEvent<Boolean>()
    internal val locationSet: LiveEvent<Boolean> get() = mLocationSet

    protected val mLocationChecked = MutableLiveEvent<LocationResult>()
    internal val locationChecked: LiveEvent<LocationResult> get() = mLocationChecked

    private val storageRootFetcher by lazy { StorageRootFetcher(app) }
    private var storageRoot: StorageRoot? = null

    abstract val isRestoreOperation: Boolean

    companion object {
        internal fun validLocationIsSet(context: Context): Boolean {
            val storage = getStorage(context) ?: return false
            if (storage.ejectable) return true
            return storage.getDocumentFile(context).isDirectory
        }
    }

    internal fun loadStorageRoots() {
        if (storageRootFetcher.getRemovableStorageListener() == null) {
            storageRootFetcher.setRemovableStorageListener(this)
        }
        Thread {
            mStorageRoots.postValue(storageRootFetcher.getStorageRoots())
        }.start()
    }

    override fun onStorageChanged() = loadStorageRoots()

    fun onStorageRootChosen(root: StorageRoot) {
        storageRoot = root
    }

    internal fun onUriPermissionGranted(result: Intent?) {
        val uri = result?.data ?: return

        // inform UI that a location has been successfully selected
        mLocationSet.setEvent(true)

        // persist permission to access backup folder across reboots
        val takeFlags = result.flags and (FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)
        app.contentResolver.takePersistableUriPermission(uri, takeFlags)

        onLocationSet(uri)
    }

    abstract fun onLocationSet(uri: Uri)

    protected fun saveStorage(uri: Uri) {
        // store backup storage location in settings
        val root = storageRoot ?: throw IllegalStateException()
        val name = if (root.isInternal()) {
            "${root.title} (${app.getString(R.string.settings_backup_location_internal)})"
        } else {
            root.title
        }
        val storage = Storage(uri, name, root.supportsEject)
        setStorage(app, storage)

        // stop backup service to be sure the old location will get updated
        app.stopService(Intent(app, ConfigurableBackupTransportService::class.java))

        Log.d(TAG, "New storage location saved: $uri")
    }

    override fun onCleared() {
        storageRootFetcher.setRemovableStorageListener(null)
        super.onCleared()
    }

}

class LocationResult(val errorMsg: String? = null)
