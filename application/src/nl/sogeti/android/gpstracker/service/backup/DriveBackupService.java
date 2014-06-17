/*------------------------------------------------------------------------------
 **     Ident: Delivery Center Java
 **    Author: rene
 ** Copyright: (c) 9 jun. 2014 Sogeti Nederland B.V. All Rights Reserved.
 **------------------------------------------------------------------------------
 ** Sogeti Nederland B.V.            |  No part of this file may be reproduced  
 ** Distributed Software Engineering |  or transmitted in any form or by any        
 ** Lange Dreef 17                   |  means, electronic or mechanical, for the      
 ** 4131 NJ Vianen                   |  purpose, without the express written    
 ** The Netherlands                  |  permission of the copyright holder.
 *------------------------------------------------------------------------------
 */
package nl.sogeti.android.gpstracker.service.backup;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import nl.sogeti.android.gpstracker.activity.LoggerMap;
import nl.sogeti.android.gpstracker.content.GPStracking.Tracks;
import nl.sogeti.android.gpstracker.tasks.xml.GpxCreator;
import nl.sogeti.android.gpstracker.tasks.xml.XmlCreator.ProgressListener;
import nl.sogeti.android.gpstracker.util.Log;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Service;
import android.content.ContentUris;
import android.content.Intent;
import android.content.IntentSender;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.drive.Drive;
import com.google.android.gms.drive.DriveApi.ContentsResult;
import com.google.android.gms.drive.DriveApi.MetadataBufferResult;
import com.google.android.gms.drive.DriveFile;
import com.google.android.gms.drive.DriveFile.DownloadProgressListener;
import com.google.android.gms.drive.DriveFolder;
import com.google.android.gms.drive.DriveFolder.DriveFileResult;
import com.google.android.gms.drive.DriveFolder.DriveFolderResult;
import com.google.android.gms.drive.DriveId;
import com.google.android.gms.drive.Metadata;
import com.google.android.gms.drive.MetadataBuffer;
import com.google.android.gms.drive.MetadataChangeSet;
import com.google.android.gms.drive.query.Filters;
import com.google.android.gms.drive.query.Query;
import com.google.android.gms.drive.query.SearchableField;
import com.google.common.io.ByteStreams;

/**
 * Creates backups of tracks to Google Drive
 * 
 * @author rene (c) 9 jun. 2014, Sogeti B.V.
 */
public class DriveBackupService extends Service implements ConnectionCallbacks, OnConnectionFailedListener, DownloadProgressListener
{

   private static final String MIME_TYPE = "application/gpx";
   private static final String FOLDER_NAME = "OpenGPSTracker";
   private GoogleApiClient googleApiClient;
   private DriveId backupFolderId;
   private MetadataBuffer fileList;
   private Uri localFile;
   private DriveFile remoteFile;
   private Activity activity;
   private Handler backgroundHandler;
   private Handler mainHandler;

   @Override
   public IBinder onBind(Intent intent)
   {
      return new LocalBinder();
   }

   @Override
   public void onDestroy()
   {
      stopBackup();
      super.onDestroy();
   }

   public void startBackup(Activity act)
   {
      this.activity = act;
      if (googleApiClient == null)
      {
         HandlerThread thread = new HandlerThread("DriveBackupService", android.os.Process.THREAD_PRIORITY_BACKGROUND);
         thread.start();
         backgroundHandler = new Handler(thread.getLooper());
         mainHandler = new Handler(Looper.getMainLooper());
         //@formatter:off
         googleApiClient = new GoogleApiClient.Builder(this)
            .addApi(Drive.API)
            .addScope(Drive.SCOPE_FILE)
            .addConnectionCallbacks(this)
            .addOnConnectionFailedListener(this)
            .setHandler(backgroundHandler)
            .build();
         //@formatter:on
      }
      if (googleApiClient.isConnected())
      {
         listRootChildren();
      }
      else
      {
         googleApiClient.connect();
      }
   }

   public void decoupleBackup()
   {
      this.activity = null;
   }

   public void stopBackup()
   {
      activity = null;
      if (googleApiClient != null)
      {
         googleApiClient.disconnect();
         googleApiClient = null;
      }
      Runnable quit = new Runnable()
         {
            @SuppressLint("NewApi")
            @Override
            public void run()
            {
               if (Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN_MR2)
               {
                  Looper.myLooper().quitSafely();
               }
               else
               {
                  Looper.myLooper().quit();
               }
            }
         };
      backgroundHandler.post(quit);
      backgroundHandler = null;
      mainHandler = null;
   }

   @Override
   public void onConnectionFailed(ConnectionResult connectionResult)
   {
      if (connectionResult.hasResolution())
      {
         try
         {
            connectionResult.startResolutionForResult(activity, LoggerMap.RESOLVE_CONNECTION_REQUEST_CODE);
         }
         catch (IntentSender.SendIntentException e)
         {
            // Unable to resolve, message user appropriately
         }
      }
      else
      {
         GooglePlayServicesUtil.getErrorDialog(connectionResult.getErrorCode(), activity, 0).show();
      }
   }

   @Override
   public void onConnected(Bundle bundle)
   {
      Log.d(this, "Start backup");
      listRootChildren();
   }

   @Override
   public void onConnectionSuspended(int arg0)
   {
      Log.d(this, "Stop backup");
      stopBackup();
   }

   private void listRootChildren()
   {
      if (googleApiClient != null)
      {
         ResultCallback<MetadataBufferResult> rootFileListing = new RootListingCallback();
         Drive.DriveApi.getRootFolder(googleApiClient).listChildren(googleApiClient).setResultCallback(rootFileListing);
      }
   }

   private void createAppFolder()
   {
      if (googleApiClient != null)
      {
         MetadataChangeSet changeSet = new MetadataChangeSet.Builder().setTitle(FOLDER_NAME).build();
         FolderCreatedCallback folderCreatedCallback = new FolderCreatedCallback();
         Drive.DriveApi.getRootFolder(googleApiClient).createFolder(googleApiClient, changeSet).setResultCallback(folderCreatedCallback);
      }
   }

   private void listAppFolderChildern()
   {
      if (googleApiClient != null)
      {
         DriveFolder folder = Drive.DriveApi.getFolder(googleApiClient, backupFolderId);
         Query query = new Query.Builder().addFilter(Filters.eq(SearchableField.MIME_TYPE, MIME_TYPE)).build();
         ResultCallback<MetadataBufferResult> gpxListingRetrievedCallback = new GpxListingRetrievedCallback();
         folder.queryChildren(googleApiClient, query).setResultCallback(gpxListingRetrievedCallback);
      }
   }

   private void pickTrackForBackup()
   {
      if (googleApiClient != null)
      {
         String order = Tracks._ID;
         String[] projection = new String[] { Tracks._ID, Tracks.NAME };
         Cursor cursor = null;
         long trackId;
         String name;
         try
         {
            cursor = getContentResolver().query(Tracks.CONTENT_URI, projection, null, null, order);
            if (cursor.moveToFirst())
            {
               do
               {
                  trackId = cursor.getLong(0);
                  name = cursor.getString(1);
                  String fileName = toDriveTitle(trackId, name);
                  if (!isStoredOnDrive(fileName))
                  {
                     Uri trackUri = ContentUris.withAppendedId(Tracks.CONTENT_URI, trackId);
                     Log.d(this, "Ready to backup " + fileName + " of " + trackUri);
                     new GpxCreator(this, trackUri, fileName, true, new GpxProgressListener()).execute();
                     break;
                  }
               }
               while (cursor.moveToNext());
            }
         }
         finally
         {
            if (cursor != null)
            {
               cursor.close();
            }
         }
      }
   }

   private void newTrackContents()
   {
      if (googleApiClient != null)
      {
         Drive.DriveApi.newContents(googleApiClient).setResultCallback(new ContentsCreate());
      }
   }

   private void createFile(ContentsResult result)
   {
      if (googleApiClient != null)
      {
         DriveFolder folder = Drive.DriveApi.getFolder(googleApiClient, backupFolderId);
         MetadataChangeSet changeSet = new MetadataChangeSet.Builder().setTitle(localFile.getLastPathSegment()).setMimeType(MIME_TYPE).setStarred(false).build();
         folder.createFile(googleApiClient, changeSet, result.getContents()).setResultCallback(new FileCallback());
      }
   }

   private void openTrackContents()
   {
      if (googleApiClient != null)
      {
         remoteFile.openContents(googleApiClient, DriveFile.MODE_WRITE_ONLY, DriveBackupService.this).setResultCallback(new ContentWriter());
      }
   }

   private void writeContents(ContentsResult results)
   {
      if (googleApiClient != null)
      {
         InputStream in = null;
         OutputStream out = null;
         try
         {
            in = getContentResolver().openInputStream(localFile);
            out = results.getContents().getOutputStream();
            long count = ByteStreams.copy(in, out);
            Log.d(this, "Copied " + count + " bytes");
         }
         catch (FileNotFoundException e)
         {
            Log.e(this, "Failed to upload contents", e);
         }
         catch (IOException e)
         {
            Log.e(this, "Failed to upload contents", e);
         }
         finally
         {
            close(in);
            close(out);
         }
         remoteFile.commitAndCloseContents(googleApiClient, results.getContents()).setResultCallback(new FileWrittenCallback());
      }
   }

   private boolean isStoredOnDrive(String filename)
   {
      boolean isStored = false;
      String localTitle = GpxCreator.cleanFilename(filename, filename) + ".gpx";
      for (Metadata remote : fileList)
      {
         String remoteTitle = remote.getTitle();
         if (localTitle.equals(remoteTitle))
         {
            Log.d(this, "Found StoredOnDrive(local:'" + localTitle + "' remote '" + remoteTitle + "')");
            isStored = remote.getFileSize() > 0;
            break;
         }
      }
      if (!isStored)
      {
         Log.d(this, "Not found StoredOnDrive(local:'" + localTitle + "')");
      }
      return isStored;
   }

   private String toDriveTitle(long id, String name)
   {
      return String.format("%d_%s", id, name.trim());
   }

   private void close(Closeable closeable)
   {
      if (closeable != null)
      {
         try
         {
            closeable.close();
         }
         catch (IOException e)
         {
            Log.e(this, "Failed to close " + closeable);
         }
      }
   }

   @Override
   public void onProgress(long bytesDownloaded, long bytesExpected)
   {
      if (activity != null)
      {
         final int value = (int) ((10000 * bytesDownloaded) / bytesExpected);
         if (activity != null)
         {
            mainHandler.post(new Runnable()
               {
                  @Override
                  public void run()
                  {
                     activity.setProgress(value);
                  }
               });
         }
      }
   }

   private class RootListingCallback implements ResultCallback<MetadataBufferResult>
   {

      @Override
      public void onResult(MetadataBufferResult result)
      {
         if (result.getStatus().isSuccess())
         {
            for (Metadata meta : result.getMetadataBuffer())
            {
               if (meta.isFolder() && meta.isEditable() && FOLDER_NAME.equals(meta.getTitle()))
               {
                  backupFolderId = meta.getDriveId();
                  listAppFolderChildern();
                  break;
               }
            }
            result.getMetadataBuffer().close();

            if (backupFolderId == null)
            {
               createAppFolder();
            }
         }
         else
         {
            Log.e(this, "Failed to list root-folder");
         }
      }
   }

   public class FolderCreatedCallback implements ResultCallback<DriveFolderResult>
   {

      @Override
      public void onResult(DriveFolderResult result)
      {
         if (result.getStatus().isSuccess())
         {
            backupFolderId = result.getDriveFolder().getDriveId();
            listAppFolderChildern();
         }
         else
         {
            Log.e(this, "Failed to create folder");
         }
      }
   }

   private class GpxListingRetrievedCallback implements ResultCallback<MetadataBufferResult>
   {

      @Override
      public void onResult(MetadataBufferResult result)
      {
         if (result.getStatus().isSuccess())
         {
            fileList = result.getMetadataBuffer();
            pickTrackForBackup();
         }
         else
         {
            Log.e(this, "Failed to list GPX files");
         }
         result.getMetadataBuffer().close();
         fileList = null;
      }

   }

   public class GpxProgressListener implements ProgressListener
   {

      @Override
      public void setIndeterminate(final boolean indeterminate)
      {
         Log.d(this, "setIndeterminate(indeterminate" + indeterminate + ")");
         if (activity != null)
         {
            mainHandler.post(new Runnable()
               {
                  @Override
                  public void run()
                  {
                     activity.setProgressBarIndeterminate(indeterminate);
                  }
               });
         }
      }

      @Override
      public void started()
      {
         Log.d(this, "started()");
         if (activity != null)
         {
            mainHandler.post(new Runnable()
               {
                  @Override
                  public void run()
                  {
                     activity.setProgressBarVisibility(true);
                  }
               });
         }
      }

      @Override
      public void setProgress(final int value)
      {
         Log.d(this, "setProgress(value" + value + ")");
         if (activity != null)
         {
            mainHandler.post(new Runnable()
               {
                  @Override
                  public void run()
                  {
                     activity.setProgress(value);
                  }
               });
         }
      }

      @Override
      public void finished(Uri result)
      {
         Log.d(this, "finished(result" + result + ")");
         activity.setProgressBarVisibility(false);
         localFile = result;
         newTrackContents();
      }

      @Override
      public void showError(String task, String errorMessage, Exception exception)
      {
         Log.d(this, "showError(errorMessage" + errorMessage + ")");
         //TODO continue
      }
   }

   private class ContentsCreate implements ResultCallback<ContentsResult>
   {
      @Override
      public void onResult(ContentsResult result)
      {
         if (result.getStatus().isSuccess())
         {
            createFile(result);
         }
         else
         {
            Log.e(this, "Failed to list GPX files");
         }
      }
   }

   public class FileCallback implements ResultCallback<DriveFileResult>
   {

      @Override
      public void onResult(DriveFileResult result)
      {
         if (result.getStatus().isSuccess())
         {
            remoteFile = result.getDriveFile();
            openTrackContents();
         }
         else
         {
            Log.e(this, "Failed to create file");
         }
      }

   }

   public class ContentWriter implements ResultCallback<ContentsResult>
   {

      @Override
      public void onResult(ContentsResult results)
      {
         if (results.getStatus().isSuccess())
         {
            writeContents(results);
         }
         else
         {
            Log.e(this, "Failed to open file contents");
         }
      }

   }

   public class FileWrittenCallback implements ResultCallback<Status>
   {

      @Override
      public void onResult(Status results)
      {
         if (results.getStatus().isSuccess())
         {
            Log.e(this, "Succes with uploading... " + localFile.getLastPathSegment());
            listAppFolderChildern();
         }
         else
         {
            Log.e(this, "Failed to close the file contents");
         }
      }
   }

   public class LocalBinder extends Binder
   {
      DriveBackupService getService()
      {
         return DriveBackupService.this;
      }
   }

}