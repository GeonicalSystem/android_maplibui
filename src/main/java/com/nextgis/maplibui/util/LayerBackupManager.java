/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.nextgis.maplibui.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Base64;

import com.hypertrack.hyperlog.HyperLog;
import com.nextgis.maplib.map.LayerOriginMetadata;
import com.nextgis.maplib.map.MapBase;
import com.nextgis.maplib.map.MapContentProviderHelper;
import com.nextgis.maplib.map.NGWVectorLayer;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.FileUtil;
import com.nextgis.maplib.util.MapUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Stores raw editable layer data before destructive sync/UI mutations.
 *
 * Backs up data tables and attachment files only. Layer config and ngfp forms are excluded so
 * Collector composition/form sync can refresh those from Web GIS while preserving locally
 * collected data for administrator recovery.
 */
public final class LayerBackupManager {
    public static final String BACKUP_DIR_NAME = "LayerBackups";
    public static final String REASON_SCHEMA_REBUILD = "schema_rebuild_after_failed_send";
    public static final String REASON_DUPLICATE_REPAIR = "duplicate_layer_repair";
    public static final String REASON_COLLECTOR_LAYER_REMOVED = "collector_layer_removed";
    public static final String REASON_SYNC_REMOTE_APPLY = "sync_remote_apply";
    public static final String REASON_MANUAL_LAYER_DELETE = "manual_layer_delete";
    public static final String REASON_MANUAL_FEATURE_DELETE = "manual_feature_delete";
    public static final String REASON_PROJECT_DELETE = "project_delete";

    public static final int DEFAULT_BACKUP_MAX_GB = 5;
    public static final long BYTES_PER_GB = 1024L * 1024L * 1024L;

    private static final String SHARE_DIR_NAME = "shared_layer_backups";
    private static final String SHARE_FILE_NAME = "ng-layer-backups.zip";
    private static final int FORMAT_VERSION = 2;

    private LayerBackupManager() {
    }

    public static BackupResult backupLayerData(
            Context context,
            NGWVectorLayer layer,
            String reason) {
        if (context == null || layer == null || layer.getPath() == null) {
            return BackupResult.failure("Invalid layer backup request");
        }

        File backupRoot = getBackupRoot(context);
        if (!ensureDirectory(backupRoot)) {
            return BackupResult.failure("Cannot create backup directory: " + backupRoot);
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
        String fileName = timestamp + "_rid" + layer.getRemoteId() + "_"
                + safeFileName(layer.getName()) + "_" + safeFileName(reason) + ".zip";
        File backupFile = new File(backupRoot, fileName);

        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(backupFile, false)))) {
            MapContentProviderHelper map = (MapContentProviderHelper) MapBase.getInstance();
            if (map == null) {
                return BackupResult.failure("Map database is not available");
            }
            SQLiteDatabase db = map.getDatabase(true);
            JSONObject manifest = buildManifest(layer, reason, timestamp, null);
            writeJsonEntry(zos, "manifest.json", manifest);
            writeTableDump(zos, db, layer.getPath().getName(), "tables/features.json",
                    null, null);
            writeTableDump(zos, db, layer.getChangeTableName(), "tables/changes.json",
                    null, null);
            writeTableDump(zos, db, layer.getAttachmentsTableName(), "tables/attachments.json",
                    null, null);
            String attachError = writeLocalAttachmentFilesToBackup(
                    zos, layer, null);
            if (attachError != null) {
                throw new IOException(attachError);
            }
        } catch (IOException | JSONException | SQLiteException | ClassCastException e) {
            HyperLog.w(Constants.TAG, "LayerBackupManager.backupLayerData: "
                    + e.getMessage(), e);
            if (backupFile.exists()) {
                // Best effort cleanup of unusable partial archives.
                backupFile.delete();
            }
            return BackupResult.failure(e.getMessage());
        }

        enforceBackupQuota(context, getMaxBackupBytes(context), backupFile);
        HyperLog.v(Constants.TAG, "Layer backup created: " + backupFile.getAbsolutePath());
        return BackupResult.success(backupFile);
    }

    /**
     * Backup selected feature rows (geometry/attributes), related change/attachment table rows,
     * and attachment files. Empty id collection is a no-op success.
     */
    public static BackupResult backupFeatures(
            Context context,
            NGWVectorLayer layer,
            Collection<Long> featureIds,
            String reason) {
        if (context == null || layer == null || layer.getPath() == null) {
            return BackupResult.failure("Invalid feature backup request");
        }
        Set<Long> ids = normalizeFeatureIds(featureIds);
        if (ids.isEmpty()) {
            return BackupResult.success(null);
        }

        File backupRoot = getBackupRoot(context);
        if (!ensureDirectory(backupRoot)) {
            return BackupResult.failure("Cannot create backup directory: " + backupRoot);
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
        String fileName = timestamp + "_rid" + layer.getRemoteId() + "_"
                + safeFileName(layer.getName()) + "_" + safeFileName(reason)
                + "_n" + ids.size() + ".zip";
        File backupFile = new File(backupRoot, fileName);

        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(backupFile, false)))) {
            MapContentProviderHelper map = (MapContentProviderHelper) MapBase.getInstance();
            if (map == null) {
                return BackupResult.failure("Map database is not available");
            }
            SQLiteDatabase db = map.getDatabase(true);
            JSONObject manifest = buildManifest(layer, reason, timestamp, ids);
            writeJsonEntry(zos, "manifest.json", manifest);
            writeTableDump(zos, db, layer.getPath().getName(), "tables/features.json",
                    Constants.FIELD_ID, ids);
            writeTableDump(zos, db, layer.getChangeTableName(), "tables/changes.json",
                    Constants.FIELD_FEATURE_ID, ids);
            writeTableDump(zos, db, layer.getAttachmentsTableName(), "tables/attachments.json",
                    Constants.FIELD_FEATURE_ID, ids);
            String attachError = writeLocalAttachmentFilesToBackup(
                    zos, layer, ids);
            if (attachError != null) {
                throw new IOException(attachError);
            }
        } catch (IOException | JSONException | SQLiteException | ClassCastException e) {
            HyperLog.w(Constants.TAG, "LayerBackupManager.backupFeatures: "
                    + e.getMessage(), e);
            if (backupFile.exists()) {
                backupFile.delete();
            }
            return BackupResult.failure(e.getMessage());
        }

        enforceBackupQuota(context, getMaxBackupBytes(context), backupFile);
        HyperLog.v(Constants.TAG, "Feature backup created: " + backupFile.getAbsolutePath()
                + " ids=" + ids.size());
        return BackupResult.success(backupFile);
    }

    public static File getBackupRoot(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getExternalCacheDir();
        }
        if (base == null) {
            base = context.getFilesDir();
        }
        return new File(base, BACKUP_DIR_NAME);
    }

    public static long getMaxBackupBytes(Context context) {
        return getMaxBackupGb(context) * BYTES_PER_GB;
    }

    public static int getMaxBackupGb(Context context) {
        if (context == null) {
            return DEFAULT_BACKUP_MAX_GB;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String raw = prefs.getString(
                SettingsConstantsUI.KEY_PREF_LAYER_BACKUP_MAX_GB,
                String.valueOf(DEFAULT_BACKUP_MAX_GB));
        try {
            int gb = Integer.parseInt(raw);
            if (gb > 0) {
                return gb;
            }
        } catch (NumberFormatException ignored) {
        }
        return DEFAULT_BACKUP_MAX_GB;
    }

    /**
     * Delete oldest ZIP archives in {@link #BACKUP_DIR_NAME} until total size is within
     * {@code maxBytes}. Never deletes {@code protectFile} while older archives remain.
     *
     * @return number of deleted files
     */
    public static int enforceBackupQuota(Context context, long maxBytes, File protectFile) {
        if (context == null) {
            return 0;
        }
        return enforceBackupQuotaOnRoot(getBackupRoot(context), maxBytes, protectFile);
    }

    /**
     * Package-visible for unit tests.
     */
    static int enforceBackupQuotaOnRoot(File root, long maxBytes, File protectFile) {
        if (root == null || maxBytes <= 0L || !root.isDirectory()) {
            return 0;
        }

        long size = FileUtil.getDirectorySize(root);
        if (size <= maxBytes) {
            return 0;
        }

        File[] children = root.listFiles();
        if (children == null || children.length == 0) {
            return 0;
        }

        List<File> zips = new ArrayList<>();
        for (File child : children) {
            if (child != null && child.isFile() && child.getName().endsWith(".zip")) {
                zips.add(child);
            }
        }
        Collections.sort(zips, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long diff = a.lastModified() - b.lastModified();
                if (diff < 0L) {
                    return -1;
                }
                if (diff > 0L) {
                    return 1;
                }
                return a.getName().compareTo(b.getName());
            }
        });

        int deleted = 0;
        for (File zip : zips) {
            if (size <= maxBytes) {
                break;
            }
            if (protectFile != null && zip.getAbsolutePath().equals(protectFile.getAbsolutePath())) {
                continue;
            }
            long fileSize = zip.length();
            if (zip.delete()) {
                size -= fileSize;
                deleted++;
                try {
                    HyperLog.v(Constants.TAG, "LayerBackupManager: quota deleted oldest "
                            + zip.getName());
                } catch (RuntimeException ignored) {
                    // HyperLog may be uninitialized in unit tests.
                }
            }
        }
        return deleted;
    }

    public static boolean hasBackups(Context context) {
        File root = getBackupRoot(context);
        return root.exists() && FileUtil.getDirectorySize(root) > 0L;
    }

    public static long getBackupDirectorySize(Context context) {
        File root = getBackupRoot(context);
        if (!root.exists()) {
            return 0L;
        }
        return FileUtil.getDirectorySize(root);
    }

    public static boolean clearBackups(Context context) {
        File root = getBackupRoot(context);
        boolean deleted = !root.exists() || FileUtil.deleteRecursive(root);
        return deleted && ensureDirectory(root);
    }

    public static File zipBackupsForShare(Context context) throws IOException {
        File root = getBackupRoot(context);
        if (!root.exists() || FileUtil.getDirectorySize(root) == 0L) {
            return null;
        }

        File temp = MapUtil.prepareTempDir(context, SHARE_DIR_NAME, false);
        if (temp == null || !ensureDirectory(temp)) {
            throw new IOException("Cannot create temporary backup share directory");
        }
        File archive = new File(temp, SHARE_FILE_NAME);
        if (archive.exists() && !archive.delete()) {
            throw new IOException("Cannot replace old backup share archive");
        }

        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(archive, false)))) {
            zipDirectoryContents(root, "", zos);
        }
        return archive;
    }

    private static Set<Long> normalizeFeatureIds(Collection<Long> featureIds) {
        Set<Long> ids = new LinkedHashSet<>();
        if (featureIds == null) {
            return ids;
        }
        for (Long id : featureIds) {
            if (id != null && id != Constants.NOT_FOUND) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static JSONObject buildManifest(
            NGWVectorLayer layer,
            String reason,
            String timestamp,
            Set<Long> featureIds) throws JSONException {
        JSONObject manifest = new JSONObject();
        manifest.put("format", "nextgis-mobile-layer-data-backup");
        manifest.put("format_version", FORMAT_VERSION);
        manifest.put("created_at_local", timestamp);
        manifest.put("reason", reason);
        manifest.put("layer_name", layer.getName());
        manifest.put("layer_path", layer.getPath().getName());
        manifest.put("account_name", layer.getAccountName());
        manifest.put("remote_id", layer.getRemoteId());
        manifest.put("sync_direction", layer.getSyncDirection());
        manifest.put("features_table", layer.getPath().getName());
        manifest.put("changes_table", layer.getChangeTableName());
        manifest.put("attachments_table", layer.getAttachmentsTableName());
        if (featureIds != null) {
            JSONArray ids = new JSONArray();
            for (Long id : featureIds) {
                ids.put(id);
            }
            manifest.put("feature_ids", ids);
            manifest.put("feature_count", featureIds.size());
            manifest.put("selective", true);
        } else {
            manifest.put("selective", false);
        }

        LayerOriginMetadata origin = layer.getLayerOriginMetadata();
        if (origin != null) {
            manifest.put("layer_origin", origin.toJSON());
        }
        return manifest;
    }

    private static void writeTableDump(
            ZipOutputStream zos,
            SQLiteDatabase db,
            String tableName,
            String entryName,
            String idColumn,
            Set<Long> featureIds) throws IOException, JSONException {
        JSONObject dump = new JSONObject();
        dump.put("table", tableName);
        if (TextUtils.isEmpty(tableName) || !tableExists(db, tableName)) {
            dump.put("exists", false);
            dump.put("columns", new JSONArray());
            dump.put("rows", new JSONArray());
            writeJsonEntry(zos, entryName, dump);
            return;
        }

        JSONArray columns = new JSONArray();
        JSONArray rows = new JSONArray();
        String sql = "SELECT * FROM " + quoteIdentifier(tableName);
        String[] args = null;
        if (featureIds != null && !featureIds.isEmpty() && !TextUtils.isEmpty(idColumn)) {
            StringBuilder in = new StringBuilder();
            List<String> argList = new ArrayList<>(featureIds.size());
            for (Long id : featureIds) {
                if (in.length() > 0) {
                    in.append(',');
                }
                in.append('?');
                argList.add(String.valueOf(id));
            }
            sql += " WHERE " + quoteIdentifier(idColumn) + " IN (" + in + ")";
            args = argList.toArray(new String[0]);
        }

        try (Cursor cursor = db.rawQuery(sql, args)) {
            String[] names = cursor.getColumnNames();
            for (String name : names) {
                columns.put(name);
            }
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int i = 0; i < names.length; i++) {
                    row.put(names[i], cursorValueToJson(cursor, i));
                }
                rows.put(row);
            }
        }
        dump.put("exists", true);
        dump.put("columns", columns);
        dump.put("row_count", rows.length());
        dump.put("rows", rows);
        writeJsonEntry(zos, entryName, dump);
    }

    private static Object cursorValueToJson(Cursor cursor, int columnIndex) throws JSONException {
        switch (cursor.getType(columnIndex)) {
            case Cursor.FIELD_TYPE_NULL:
                return JSONObject.NULL;
            case Cursor.FIELD_TYPE_INTEGER:
                return cursor.getLong(columnIndex);
            case Cursor.FIELD_TYPE_FLOAT:
                return cursor.getDouble(columnIndex);
            case Cursor.FIELD_TYPE_BLOB:
                JSONObject blob = new JSONObject();
                blob.put("type", "blob");
                blob.put("base64", Base64.encodeToString(
                        cursor.getBlob(columnIndex), Base64.NO_WRAP));
                return blob;
            case Cursor.FIELD_TYPE_STRING:
            default:
                return cursor.getString(columnIndex);
        }
    }

    private static boolean tableExists(SQLiteDatabase db, String tableName) {
        try (Cursor cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{tableName})) {
            return cursor != null && cursor.moveToFirst();
        }
    }

    private static void writeJsonEntry(
            ZipOutputStream zos,
            String entryName,
            JSONObject json) throws IOException, JSONException {
        zos.putNextEntry(new ZipEntry(entryName));
        byte[] bytes = json.toString(2).getBytes(StandardCharsets.UTF_8);
        zos.write(bytes);
        zos.closeEntry();
    }

    /**
     * Write only attachment payloads that are physically present in the layer directory.
     * Server attachment metadata is already retained in tables/attachments.json and must not
     * turn a local backup into a network operation.
     *
     * @return null on success, or a human-readable failure reason
     */
    private static String writeLocalAttachmentFilesToBackup(
            ZipOutputStream zos,
            NGWVectorLayer layer,
            Set<Long> featureIds) {
        try {
            writeLocalAttachmentFiles(zos, layer.getPath(), featureIds);
        } catch (IOException e) {
            return "Failed to pack local attachment files: " + e.getMessage();
        }
        return null;
    }

    static void writeLocalAttachmentFiles(
            ZipOutputStream zos,
            File layerPath,
            Set<Long> featureIds) throws IOException {
        File[] children = layerPath.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (!child.isDirectory() || !isLong(child.getName())) {
                continue;
            }
            long featureId;
            try {
                featureId = Long.parseLong(child.getName());
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (featureIds != null && !featureIds.contains(featureId)) {
                continue;
            }
            zipFileOrDirectory(child, "attachments/" + child.getName(), zos);
        }
    }

    private static void zipDirectoryContents(
            File directory,
            String prefix,
            ZipOutputStream zos) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String entryName = prefix + child.getName();
            zipFileOrDirectory(child, entryName, zos);
        }
    }

    private static void zipFileOrDirectory(
            File file,
            String entryName,
            ZipOutputStream zos) throws IOException {
        if (file.isDirectory()) {
            zipDirectoryContents(file, entryName + "/", zos);
            return;
        }
        zos.putNextEntry(new ZipEntry(entryName));
        byte[] buffer = new byte[8192];
        int length;
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            while ((length = input.read(buffer)) > 0) {
                zos.write(buffer, 0, length);
            }
        }
        zos.closeEntry();
    }

    private static boolean ensureDirectory(File directory) {
        return directory.isDirectory() || directory.mkdirs();
    }

    private static boolean isLong(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String safeFileName(String value) {
        if (TextUtils.isEmpty(value)) {
            return "layer";
        }
        return value.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    public static final class BackupResult {
        private final boolean mSuccess;
        private final File mFile;
        private final String mError;

        private BackupResult(boolean success, File file, String error) {
            mSuccess = success;
            mFile = file;
            mError = error;
        }

        public static BackupResult success(File file) {
            return new BackupResult(true, file, null);
        }

        public static BackupResult failure(String error) {
            return new BackupResult(false, null, error);
        }

        public boolean isSuccess() {
            return mSuccess;
        }

        public File getFile() {
            return mFile;
        }

        public String getError() {
            return mError;
        }
    }
}
