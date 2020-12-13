package com.anggrayudi.storage.file

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.SimpleStorage
import com.anggrayudi.storage.extension.*
import java.io.File
import java.io.IOException
import java.net.URLDecoder

/**
 * Created on 16/08/20
 * @author Anggrayudi H
 */
object DocumentFileCompat {

    const val PRIMARY = "primary"

    const val MIME_TYPE_UNKNOWN = "*/*"

    const val MIME_TYPE_BINARY_FILE = "application/octet-stream"

    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    const val DOWNLOADS_FOLDER_AUTHORITY = "com.android.providers.downloads.documents"

    const val MEDIA_FOLDER_AUTHORITY = "com.android.providers.media.documents"

    val FILE_NAME_DUPLICATION_REGEX_WITH_EXTENSION = Regex("(.*?) \\(\\d+\\)\\.[a-zA-Z0-9]+")

    val FILE_NAME_DUPLICATION_REGEX_WITHOUT_EXTENSION = Regex("(.*?) \\(\\d+\\)")

    @JvmStatic
    fun isRootUri(uri: Uri): Boolean {
        val path = uri.path ?: return false
        return uri.authority == EXTERNAL_STORAGE_AUTHORITY && path.indexOf(':') == path.length - 1
    }

    /**
     * If given [Uri] with path `/tree/primary:Downloads/MyVideo.mp4`, then return `primary`.
     */
    @JvmStatic
    fun getStorageId(uri: Uri): String {
        val path = uri.path.orEmpty()
        return if (uri.scheme == ContentResolver.SCHEME_FILE) {
            File(path).storageId
        } else {
            if (uri.authority == EXTERNAL_STORAGE_AUTHORITY) path.substringBefore(':', "").substringAfterLast('/') else ""
        }
    }

    /**
     * @param fullPath For SD card can be full path `storage/6881-2249/Music` or simple path `6881-2249:Music`.
     *             For primary storage can be `/storage/emulated/0/Music` or simple path `primary:Music`.
     */
    @JvmStatic
    fun getStorageId(fullPath: String): String {
        return if (fullPath.startsWith('/')) {
            if (fullPath.startsWith(SimpleStorage.externalStoragePath)) {
                PRIMARY
            } else {
                fullPath.substringAfter("/storage/", "").substringBefore('/')
            }
        } else {
            fullPath.substringBefore(':', "").substringAfterLast('/')
        }
    }

    /**
     * @param fullPath For SD card can be full path `storage/6881-2249/Music` or simple path `6881-2249:Music`.
     *             For primary storage can be `/storage/emulated/0/Music` or simple path `primary:Music`.
     * @return Given `storage/6881-2249/Music/My Love.mp3`, then return `Music/My Love.mp3`.
     *          May return empty `String` if it is a root path of the storage.
     */
    // TODO: 12/12/20 Find better terminology for direct path
    @JvmStatic
    fun getDirectPath(fullPath: String): String {
        val directPath = if (fullPath.startsWith('/')) {
            val externalStoragePath = SimpleStorage.externalStoragePath
            if (fullPath.startsWith(externalStoragePath)) {
                fullPath.substringAfter(externalStoragePath)
            } else {
                fullPath.substringAfter("/storage/", "").substringAfter('/', "")
            }
        } else {
            fullPath.substringAfter(':', "")
        }
        return directPath.trimFileSeparator().removeForbiddenCharsFromFilename()
    }

    /**
     * @param storageId If in SD card, it should be integers like `6881-2249`. Otherwise, if in external storage it will be [PRIMARY].
     * @param directPath If in Downloads folder of SD card, it will be `Downloads/MyMovie.mp4`.
     *                 If in external storage it will be `Downloads/MyMovie.mp4` as well.
     */
    @JvmOverloads
    @JvmStatic
    fun fromSimplePath(
        context: Context,
        storageId: String = PRIMARY,
        directPath: String = "",
        documentType: DocumentFileType = DocumentFileType.ANY,
        considerRawFile: Boolean = true
    ): DocumentFile? {
        return if (directPath.isEmpty()) {
            getRootDocumentFile(context, storageId, considerRawFile)
        } else {
            exploreFile(context, storageId, directPath, documentType, considerRawFile)
        }
    }

    /**
     * `fileFullPath` for example:
     * * For file in external storage => `/storage/emulated/0/Downloads/MyMovie.mp4`.
     * * For file in SD card => `/storage/9016-4EF8/Downloads/MyMovie.mp4` or you can input simple path like this `9016-4EF8:Downloads/MyMovie.mp4`.
     *                          You can input `9016-4EF8:` or `/storage/9016-4EF8` for SD card's root path.
     * @see DocumentFile.absolutePath
     */
    @JvmOverloads
    @JvmStatic
    fun fromFullPath(
        context: Context,
        fullPath: String,
        documentType: DocumentFileType = DocumentFileType.ANY,
        considerRawFile: Boolean = true
    ): DocumentFile? {
        return if (fullPath.startsWith('/')) {
            // absolute path
            fromFile(context, File(fullPath), documentType, considerRawFile)
        } else {
            // simple path
            fromSimplePath(context, fullPath.substringBefore(':'), fullPath.substringAfter(':'), documentType, considerRawFile)
        }
    }

    /**
     * Since Android 10, only app directory that is accessible by [File], e.g. `/storage/emulated/0/Android/data/com.anggrayudi.storage.sample/files`
     *
     * To continue using [File], you need to request full storage access via [SimpleStorage.requestFullStorageAccess]
     *
     * This function allows you to read and write files in external storage, regardless of API levels.
     *
     * @param considerRawFile `true` if you want to consider faster performance with [File]
     * @return `TreeDocumentFile` if `considerRawFile` is false, or if the given [File] can be read with URI permission only, otherwise return `RawDocumentFile`
     */
    @JvmOverloads
    @JvmStatic
    fun fromFile(
        context: Context,
        file: File,
        documentType: DocumentFileType = DocumentFileType.ANY,
        considerRawFile: Boolean = true
    ): DocumentFile? {
        return if (considerRawFile && file.canRead()) {
            if (documentType == DocumentFileType.FILE && !file.isFile || documentType == DocumentFileType.FOLDER && !file.isDirectory)
                null
            else
                DocumentFile.fromFile(file)
        } else {
            val directPath = file.directPath.removeForbiddenCharsFromFilename().trimFileSeparator()
            exploreFile(context, file.storageId, directPath, documentType, considerRawFile)
        }
    }

    /**
     * Returns `null` if folder does not exist or you have no permission on this directory
     */
    @JvmOverloads
    @JvmStatic
    @Suppress("DEPRECATION")
    fun fromPublicFolder(
        context: Context,
        type: PublicDirectory,
        requiresWriteAccess: Boolean = false,
        considerRawFile: Boolean = true
    ): DocumentFile? {
        val rawFile = Environment.getExternalStoragePublicDirectory(type.folderName)
        if (considerRawFile && rawFile.canRead() && (requiresWriteAccess && rawFile.canWrite() || !requiresWriteAccess)) {
            return DocumentFile.fromFile(rawFile)
        }

        val folder = if (type == PublicDirectory.DOWNLOADS) {
            val downloadFolder = context.fromTreeUri(Uri.parse("content://$DOWNLOADS_FOLDER_AUTHORITY/tree/downloads"))
            if (downloadFolder?.canRead() == true) downloadFolder else fromFullPath(context, rawFile.absolutePath, DocumentFileType.FOLDER, false)
        } else {
            fromFullPath(context, rawFile.absolutePath, DocumentFileType.FOLDER, false)
        }
        return folder?.takeIf { it.canRead() && (requiresWriteAccess && folder.canWrite() || !requiresWriteAccess) }
    }

    /**
     * To get root file access on API 30+, you need to have full storage access by
     * granting [Manifest.permission.MANAGE_EXTERNAL_STORAGE] in runtime.
     * @see SimpleStorage.requestFullStorageAccess
     * @see SimpleStorage.hasFullStorageAccess
     * @see Environment.isExternalStorageManager
     * @see getRootRawFile
     */
    @JvmOverloads
    @JvmStatic
    fun getRootDocumentFile(
        context: Context,
        storageId: String,
        requiresWriteAccess: Boolean = false,
        considerRawFile: Boolean = true
    ): DocumentFile? {
        return if (considerRawFile) {
            getRootRawFile(storageId, requiresWriteAccess)?.let { DocumentFile.fromFile(it) }
                ?: context.fromTreeUri(createDocumentUri(storageId))
        } else {
            context.fromTreeUri(createDocumentUri(storageId))
        }
    }

    /**
     * In API 29+, `/storage/emulated/0` may not be granted for URI permission,
     * but all directories under `/storage/emulated/0/Music` are granted and accessible.
     *
     * For example, given `/storage/emulated/0/Music/Metal`, then return `/storage/emulated/0/Music`
     *
     * @param fullPath construct it using [buildAbsolutePath] or [buildSimplePath]
     * @return `null` if accessible root path is not found in [ContentResolver.getPersistedUriPermissions], or the folder does not exist.
     */
    @JvmOverloads
    @JvmStatic
    fun getAccessibleRootDocumentFile(
        context: Context,
        fullPath: String,
        requiresWriteAccess: Boolean = false,
        considerRawFile: Boolean = true
    ): DocumentFile? {
        if (considerRawFile && fullPath.startsWith('/')) {
            val rootFile = File(fullPath).getRootRawFile(requiresWriteAccess)
            if (rootFile != null) {
                return DocumentFile.fromFile(rootFile)
            }
        }
        val storageId = getStorageId(fullPath)
        if (storageId.isNotEmpty()) {
            val cleanDirectPath = getDirectPath(fullPath)
            context.contentResolver.persistedUriPermissions
                // For instance, content://com.android.externalstorage.documents/tree/primary%3AMusic
                .filter { it.isReadPermission && it.isWritePermission }
                .forEach {
                    val uriPath = it.uri.path // e.g. /tree/primary:Music
                    if (uriPath != null) {
                        val currentStorageId = uriPath.substringBefore(':').substringAfterLast('/')
                        val currentRootFolder = uriPath.substringAfter(':', "")
                        if (currentStorageId == storageId && (currentRootFolder.isEmpty() || cleanDirectPath.hasParent(currentRootFolder))) {
                            return context.fromTreeUri(it.uri)
                        }
                    }
                }
        }
        return null
    }

    /**
     * To get root file access on API 30+, you need to have full storage access by
     * granting [Manifest.permission.MANAGE_EXTERNAL_STORAGE] in runtime.
     * @see SimpleStorage.requestFullStorageAccess
     * @see SimpleStorage.hasFullStorageAccess
     * @see Environment.isExternalStorageManager
     * @return `null` if you have no full storage access
     */
    @JvmOverloads
    @JvmStatic
    @Suppress("DEPRECATION")
    fun getRootRawFile(storageId: String, requiresWriteAccess: Boolean = false): File? {
        val rootFile = if (storageId == PRIMARY) {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage/$storageId")
        }
        return rootFile.takeIf { rootFile.canRead() && (requiresWriteAccess && rootFile.canWrite() || !requiresWriteAccess) }
    }

    @JvmStatic
    fun buildAbsolutePath(storageId: String = PRIMARY, directPath: String): String {
        val cleanDirectPath = directPath.trimEnd('/').removeForbiddenCharsFromFilename()
        return if (storageId == PRIMARY) {
            SimpleStorage.externalStoragePath + "/$cleanDirectPath"
        } else {
            "/storage/$storageId/$cleanDirectPath"
        }
    }

    @JvmStatic
    fun buildAbsolutePath(simplePath: String): String {
        val path = simplePath.trimEnd('/')
        return if (path.startsWith('/')) {
            path.removeForbiddenCharsFromFilename()
        } else {
            buildAbsolutePath(getStorageId(path), getDirectPath(path))
        }
    }

    @JvmStatic
    fun buildSimplePath(storageId: String = PRIMARY, directPath: String): String {
        val cleanDirectPath = directPath.removeForbiddenCharsFromFilename().trimFileSeparator()
        return "$storageId:$cleanDirectPath"
    }

    @JvmStatic
    fun buildSimplePath(absolutePath: String): String {
        return buildSimplePath(getStorageId(absolutePath), getDirectPath(absolutePath))
    }

    @JvmOverloads
    @JvmStatic
    fun createDocumentUri(storageId: String, directPath: String = ""): Uri =
        Uri.parse("content://$EXTERNAL_STORAGE_AUTHORITY/tree/" + Uri.encode("$storageId:$directPath"))

    @JvmStatic
    fun isAccessGranted(context: Context, storageId: String): Boolean {
        return storageId == PRIMARY && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || getRootDocumentFile(context, storageId, true) != null
    }

    @JvmStatic
    fun doesExist(context: Context, fullPath: String) = fromFullPath(context, fullPath)?.exists() == true

    @JvmStatic
    fun delete(context: Context, fullPath: String) = fromFullPath(context, fullPath)?.delete() == true

    /**
     * Check if storage has URI permission for read and write access.
     *
     * Persisted URIs revoked whenever the related folders deleted. Hence, you need to request URI permission again even though the folder
     * recreated by user. However, you should not worry about this on API 28 and lower, because URI permission always granted for root path
     * and rooth path itself can't be deleted.
     */
    @JvmOverloads
    @JvmStatic
    fun isStorageUriPermissionGranted(context: Context, storageId: String, directPath: String = ""): Boolean {
        val root = createDocumentUri(storageId, directPath)
        return context.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.isWritePermission && it.uri == root }
    }

    /**
     * Get all storage IDs on this device. The first index is primary storage.
     * Prior to API 28, retrieving storage ID for SD card only applicable if URI permission is granted for read & write access.
     */
    @JvmStatic
    fun getStorageIds(context: Context): List<String> {
        val externalStoragePath = SimpleStorage.externalStoragePath
        val storageIds = ContextCompat.getExternalFilesDirs(context, null).map {
            val path = it.path
            if (path.startsWith(externalStoragePath)) {
                // Path -> /storage/emulated/0/Android/data/com.anggrayudi.storage.sample/files
                PRIMARY
            } else {
                // Path -> /storage/131D-261A/Android/data/com.anggrayudi.storage.sample/files
                path.substringAfter("/storage/").substringBefore('/')
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            storageIds
        } else {
            val persistedStorageIds = context.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission && it.isWritePermission }
                .mapNotNull { it.uri.path?.run { substringBefore(':', "").substringAfterLast('/') } }
            storageIds.toMutableList().run {
                addAll(persistedStorageIds)
                distinct()
            }
        }
    }

    @JvmStatic
    fun getSdCardIds(context: Context) = getStorageIds(context).filter { it != PRIMARY }

    /**
     * Create folders. You should do this process in background.
     *
     * @param fullPath construct it using [buildAbsolutePath] or [buildSimplePath]
     * @param requiresWriteAccess the folder should have write access, otherwise return `null`
     * @return `null` if you have no storage permission.
     */
    @JvmOverloads
    @JvmStatic
    fun mkdirs(context: Context, fullPath: String, requiresWriteAccess: Boolean = true, considerRawFile: Boolean = true): DocumentFile? {
        if (considerRawFile && fullPath.startsWith('/')) {
            val folder = File(fullPath.removeForbiddenCharsFromFilename()).apply { mkdirs() }
            if (folder.isDirectory && folder.canRead() && (requiresWriteAccess && folder.canWrite() || !requiresWriteAccess)) {
                // Consider java.io.File for faster performance
                return DocumentFile.fromFile(folder)
            }
        }
        var currentDirectory = getAccessibleRootDocumentFile(context, fullPath, requiresWriteAccess, considerRawFile) ?: return null
        getDirectorySequence(getDirectPath(fullPath)).forEach {
            try {
                val directory = currentDirectory.findFolder(it)
                if (directory == null)
                    currentDirectory = currentDirectory.createDirectory(it) ?: return null
                else if (directory.canRead())
                    currentDirectory = directory
            } catch (e: Exception) {
                return null
            }
        }
        return currentDirectory.takeIf { requiresWriteAccess && it.canWrite() || !requiresWriteAccess }
    }

    /**
     * Optimized performance for creating multiple folders. The result may contains `null` elements for unsuccessful creation.
     * For instance, if parameter `fullPaths` contains 5 elements and successful `mkdirs()` is 3, then return 3 non-null elements + 2 null elements.
     *
     * @param fullPaths either simple path or absolute path. Tips: use [buildAbsolutePath] or [buildSimplePath] to construct full path.
     * @param requiresWriteAccess the folder should have write access, otherwise return `null`
     */
    @JvmOverloads
    @JvmStatic
    fun mkdirs(
        context: Context,
        fullPaths: List<String>,
        requiresWriteAccess: Boolean = true,
        considerRawFile: Boolean = true
    ): Array<DocumentFile?> {
        val results = arrayOfNulls<DocumentFile>(fullPaths.size)
        val cleanedFullPaths = fullPaths.map { buildAbsolutePath(it) }
        for (path in findUniqueDeepestSubFolders(cleanedFullPaths)) {
            // use java.io.File for faster performance
            val folder = File(path).apply { mkdirs() }
            if (considerRawFile && folder.isDirectory && folder.canRead()) {
                cleanedFullPaths.forEachIndexed { index, s ->
                    if (path.hasParent(s)) {
                        results[index] = DocumentFile.fromFile(File(getDirectorySequence(s).joinToString(prefix = "/", separator = "/")))
                    }
                }
            } else {
                var currentDirectory = getAccessibleRootDocumentFile(context, path, requiresWriteAccess, considerRawFile) ?: continue
                getDirectorySequence(getDirectPath(path)).forEach {
                    try {
                        val directory = currentDirectory.findFolder(it)
                        if (directory == null) {
                            currentDirectory = currentDirectory.createDirectory(it) ?: return@forEach
                            val fullPath = currentDirectory.absolutePath
                            cleanedFullPaths.forEachIndexed { index, s ->
                                if (fullPath == s) {
                                    results[index] = currentDirectory
                                }
                            }
                        } else if (directory.canRead()) {
                            currentDirectory = directory
                            val fullPath = directory.absolutePath
                            cleanedFullPaths.forEachIndexed { index, s ->
                                if (fullPath == s) {
                                    results[index] = directory
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        return@forEach
                    }
                }
            }
        }
        results.indices.forEach { index ->
            results[index] = results[index]?.takeIf { requiresWriteAccess && it.canWrite() || !requiresWriteAccess }
        }
        return results
    }

    /**
     * @return `null` if you don't have storage permission.
     * @param directPath file path without root path, e.g. `/storage/emulated/0/Music/Pop` should be written as `Music/Pop`
     */
    @JvmOverloads
    @JvmStatic
    fun createFile(
        context: Context,
        storageId: String = PRIMARY,
        directPath: String,
        mimeType: String = MIME_TYPE_UNKNOWN,
        considerRawFile: Boolean = true
    ): DocumentFile? {
        return if (considerRawFile && storageId == PRIMARY && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val file = File(buildAbsolutePath(storageId, directPath))
            file.parentFile?.mkdirs()
            if (create(file)) DocumentFile.fromFile(file) else null
        } else try {
            val directory = mkdirsParentDirectory(context, storageId, directPath, considerRawFile)
            val filename = getFileNameFromPath(directPath).removeForbiddenCharsFromFilename()
            if (filename.isEmpty()) null else directory?.makeFile(mimeType, filename)
        } catch (e: Exception) {
            null
        }
    }

    private fun getParentPath(path: String): String? = getDirectorySequence(path).let { it.getOrNull(it.size - 2) }

    private fun mkdirsParentDirectory(context: Context, storageId: String, directPath: String, considerRawFile: Boolean): DocumentFile? {
        val parentPath = getParentPath(directPath)
        return if (parentPath != null) {
            mkdirs(context, buildAbsolutePath(storageId, parentPath), considerRawFile)
        } else {
            getRootDocumentFile(context, storageId, true, considerRawFile)
        }
    }

    private fun getFileNameFromPath(path: String) = path.trimEnd('/').substringAfterLast('/')

    @JvmOverloads
    @JvmStatic
    fun recreate(
        context: Context,
        storageId: String = PRIMARY,
        directPath: String,
        mimeType: String = MIME_TYPE_UNKNOWN,
        considerRawFile: Boolean = true
    ): DocumentFile? {
        val file = File(buildAbsolutePath(storageId, directPath))
        file.delete()
        file.parentFile?.mkdirs()
        if (considerRawFile && create(file)) {
            return DocumentFile.fromFile(file)
        }

        val directory = mkdirsParentDirectory(context, storageId, directPath, considerRawFile)
        val filename = file.name
        if (filename.isNullOrEmpty()) {
            return null
        }
        return directory?.run {
            findFile(filename)?.delete()
            makeFile(mimeType, filename)
        }
    }

    private fun create(file: File): Boolean {
        return try {
            file.isFile && file.length() == 0L || file.createNewFile()
        } catch (e: IOException) {
            false
        }
    }

    internal fun String.removeForbiddenCharsFromFilename(): String = replace(":", "_")
        .replaceCompletely("//", "/")

    private fun exploreFile(
        context: Context,
        storageId: String,
        directPath: String,
        documentType: DocumentFileType,
        considerRawFile: Boolean
    ): DocumentFile? {
        val rawFile = File(buildAbsolutePath(storageId, directPath))
        if (considerRawFile && rawFile.canRead()) {
            return if (documentType == DocumentFileType.ANY || documentType == DocumentFileType.FILE && rawFile.isFile
                || documentType == DocumentFileType.FOLDER && rawFile.isDirectory
            ) {
                DocumentFile.fromFile(rawFile)
            } else {
                null
            }
        }
        val file = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            var current = getRootDocumentFile(context, storageId, considerRawFile) ?: return null
            getDirectorySequence(directPath).forEach {
                current = current.findFile(it) ?: return null
            }
            current
        } else {
            val directorySequence = getDirectorySequence(directPath).toMutableList()
            val parentTree = ArrayList<String>(directorySequence.size)
            var grantedFile: DocumentFile? = null
            // Find granted file tree.
            // For example, /storage/emulated/0/Music may not granted, but /storage/emulated/0/Music/Pop is granted by user.
            while (directorySequence.isNotEmpty()) {
                parentTree.add(directorySequence.removeFirst())
                val folderTree = parentTree.joinToString(separator = "/")
                try {
                    grantedFile = context.fromTreeUri(createDocumentUri(storageId, folderTree))
                    if (grantedFile?.canRead() == true) break
                } catch (e: SecurityException) {
                    // ignore
                }
            }
            if (grantedFile == null || directorySequence.isEmpty()) {
                grantedFile
            } else {
                val fileTree = directorySequence.joinToString(prefix = "/", separator = "/")
                context.fromTreeUri(Uri.parse(grantedFile.uri.toString() + Uri.encode(fileTree)))
            }
        }
        return file?.takeIf {
            it.canRead() && (documentType == DocumentFileType.ANY
                    || documentType == DocumentFileType.FILE && it.isFile || documentType == DocumentFileType.FOLDER && it.isDirectory)
        }
    }

    /**
     * For example, `Downloads/Video/Sports/` will become array `["Downloads", "Video", "Sports"]`
     */
    private fun getDirectorySequence(path: String) = path.split('/')
        .filterNot { it.isBlank() }

    private fun recreateAppDirectory(context: Context) = context.getAppDirectory().apply { File(this).mkdirs() }

    /**
     * Given the following `folderFullPaths`:
     * * `/storage/9016-4EF8/Downloads`
     * * `/storage/9016-4EF8/Downloads/Archive`
     * * `/storage/9016-4EF8/Video`
     * * `/storage/9016-4EF8/Music`
     * * `/storage/9016-4EF8/Music/Favorites/Pop`
     * * `/storage/emulated/0/Music`
     * * `primary:Alarm/Morning`
     * * `primary:Alarm`
     *
     * Then return:
     * * `/storage/9016-4EF8/Downloads/Archive`
     * * `/storage/9016-4EF8/Music/Favorites/Pop`
     * * `/storage/9016-4EF8/Video`
     * * `/storage/emulated/0/Music`
     * * `/storage/emulated/0/Alarm/Morning`
     */
    @JvmStatic
    fun findUniqueDeepestSubFolders(folderFullPaths: Collection<String>): List<String> {
        val paths = folderFullPaths.map { buildAbsolutePath(it) }.distinct()
        val results = ArrayList(paths)
        paths.forEach { path ->
            paths.find { it != path && path.hasParent(it) }?.let { results.remove(it) }
        }
        return results
    }

    /**
     * Given the following `folderFullPaths`:
     * * `/storage/9016-4EF8/Downloads`
     * * `/storage/9016-4EF8/Downloads/Archive`
     * * `/storage/9016-4EF8/Video`
     * * `/storage/9016-4EF8/Music`
     * * `/storage/9016-4EF8/Music/Favorites/Pop`
     * * `/storage/emulated/0/Music`
     * * `primary:Alarm/Morning`
     * * `primary:Alarm`
     *
     * Then return:
     * * `/storage/9016-4EF8/Downloads`
     * * `/storage/9016-4EF8/Music`
     * * `/storage/9016-4EF8/Video`
     * * `/storage/emulated/0/Music`
     * * `/storage/emulated/0/Alarm`
     */
    @JvmStatic
    fun findUniqueParents(folderFullPaths: Collection<String>): List<String> {
        val paths = folderFullPaths.map { buildAbsolutePath(it) }.distinct()
        val results = ArrayList<String>(paths.size)
        paths.forEach { path ->
            if (!paths.any { it != path && path.hasParent(it) }) {
                results.add(path)
            }
        }
        return results
    }

    @JvmStatic
    @WorkerThread
    fun findInaccessibleStorageLocations(context: Context, fullPaths: List<String>): List<String> {
        return if (SimpleStorage.hasStoragePermission(context)) {
            val uniqueParents = findUniqueParents(fullPaths)
            val inaccessibleStorageLocations = ArrayList<String>(uniqueParents.size)
            // if folder not found, try create it and check whether is successful
            mkdirs(context, uniqueParents).forEachIndexed { index, folder ->
                if (folder == null) {
                    inaccessibleStorageLocations.add(uniqueParents[index])
                }
            }
            inaccessibleStorageLocations
        } else {
            fullPaths.map { buildAbsolutePath(it) }
        }
    }

    /** Get available space in bytes. */
    @JvmStatic
    @Suppress("DEPRECATION")
    fun getFreeSpace(context: Context, storageId: String): Long {
        try {
            if (storageId == PRIMARY) {
                val stat = StatFs(recreateAppDirectory(context))
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2)
                    stat.availableBytes
                else
                    (stat.blockSize * stat.availableBlocks).toLong()
            } else if (Build.VERSION.SDK_INT >= 21) {
                try {
                    val fileUri = getDocumentFileForStorageInfo(context, storageId)?.uri ?: return 0
                    context.contentResolver.openFileDescriptor(fileUri, "r")?.use {
                        val stats = Os.fstatvfs(it.fileDescriptor)
                        return stats.f_bavail * stats.f_frsize
                    }
                } catch (e: ErrnoException) {
                    // ignore
                }
            }
        } catch (e: SecurityException) {
            // ignore
        } catch (e: IllegalArgumentException) {
            // ignore
        } catch (e: IOException) {
            // ignore
        }
        return 0
    }

    /** Get available space in bytes. */
    @JvmStatic
    @Suppress("DEPRECATION")
    fun getUsedSpace(context: Context, storageId: String): Long {
        try {
            if (storageId == PRIMARY) {
                val stat = StatFs(recreateAppDirectory(context))
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2)
                    stat.totalBytes - stat.availableBytes
                else
                    (stat.blockSize * stat.blockCount - stat.blockSize * stat.availableBlocks).toLong()
            } else if (Build.VERSION.SDK_INT >= 21) {
                try {
                    val fileUri = getDocumentFileForStorageInfo(context, storageId)?.uri ?: return 0
                    context.contentResolver.openFileDescriptor(fileUri, "r")?.use {
                        val stats = Os.fstatvfs(it.fileDescriptor)
                        return stats.f_blocks * stats.f_frsize - stats.f_bavail * stats.f_frsize
                    }
                } catch (e: ErrnoException) {
                    // ignore
                }
            }
        } catch (e: SecurityException) {
            // ignore
        } catch (e: IllegalArgumentException) {
            // ignore
        } catch (e: IOException) {
            // ignore
        }
        return 0
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    fun getStorageCapacity(context: Context, storageId: String): Long {
        try {
            if (storageId == PRIMARY) {
                val stat = StatFs(recreateAppDirectory(context))
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2)
                    stat.totalBytes
                else
                    (stat.blockSize * stat.blockCount).toLong()
            } else if (Build.VERSION.SDK_INT >= 21) {
                try {
                    val fileUri = getDocumentFileForStorageInfo(context, storageId)?.uri ?: return 0
                    context.contentResolver.openFileDescriptor(fileUri, "r")?.use {
                        val stats = Os.fstatvfs(it.fileDescriptor)
                        return stats.f_blocks * stats.f_frsize
                    }
                } catch (e: ErrnoException) {
                    // ignore
                }
            }
        } catch (e: SecurityException) {
            // ignore
        } catch (e: IllegalArgumentException) {
            // ignore
        } catch (e: IOException) {
            // ignore
        }
        return 0
    }

    private fun getDocumentFileForStorageInfo(context: Context, storageId: String): DocumentFile? {
        return if (storageId == PRIMARY) {
            // use app private directory, so no permissions required
            val privateAppDirectory = context.getExternalFilesDir(null) ?: return null
            privateAppDirectory.mkdirs()
            DocumentFile.fromFile(privateAppDirectory)
        } else {
            getAccessibleRootDocumentFile(context, getStorageIds(context).find { it == storageId } ?: return null)
        }
    }

    @JvmStatic
    fun getFileNameFromUrl(url: String): String {
        return try {
            URLDecoder.decode(url, "UTF-8").substringAfterLast('/')
        } catch (e: Exception) {
            url
        }
    }
}