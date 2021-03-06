package com.davenotdavid.musicplayerlite;

import android.Manifest;
import android.app.LoaderManager;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.DialogInterface;
import android.content.Loader;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.widget.AdapterView;
import android.widget.ListView;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.os.IBinder;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.view.MenuItem;
import android.widget.MediaController.MediaPlayerControl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.davenotdavid.musicplayerlite.MusicService.MusicBinder;

/**
 * Music player app that initially retrieves the user's songs from their music library, and then
 * provides playback functionality.
 */
public class MainActivity extends AppCompatActivity implements MediaPlayerControl,
        LoaderCallbacks<List<Song>> {

    // Log tag constant.
    private static final String LOG_TAG = MainActivity.class.getSimpleName();

    // Constant used as a parameter to assist with the permission requesting process.
    private final int PERMISSION_CODE = 1;

    // Song list field.
    private List<Song> mSongList;

    // ListView field of the songs.
    private ListView mSongListView;

    // Adapter for the list of songs.
    public static SongAdapter mSongAdapter;

    // Fields used for binding the interaction between the Activity and the Service class - the
    // music will be played in the Service class, but be controlled from the Activity.
    private MusicService mMusicService;
    private Intent mPlayIntent;
    private boolean mMusicBound;

    // Field used for setting the controller up.
    private static MusicController mController;

    // Boolean flag that's used to address when the user interacts with the controls while playback
    // is paused since the MediaPlayer object may behave strangely.
    public static boolean mPlaybackPaused = false;

    // Loader ID field that gets incremented whenever a user deletes a song.
    private int songLoaderID = 1;

    // Widget field used for displaying a progress bar while running the loader.
    private ProgressBar mProgressBar;

    // TextView that is displayed when the list is empty.
    private TextView mEmptyStateTextView;

    // Boolean flag that's used to indicate whether the loader is done or not for the sake of
    // setting up the song list, accordingly.
    private boolean mLoadFinished;

    // Static int field used for tracking the song's position for UI-updating purposes.
    public static int songPosition = -1;

    // Int field used for tracking the song's position only for menu-option purposes.
    private int mSongPositionOptions;

    // Static boolean flags used for implementing shuffle and auto-repeat functionality,
    // respectively.
    public static boolean mShuffle, mAutoRepeat;

    // Phone state interface initialization in order to react accordingly when the user gets a
    // phone call.
    private PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            switch (state) {
                case TelephonyManager.CALL_STATE_RINGING:
                    Log.d(LOG_TAG, "User's device ringing");

                    // Pauses the player and the controller hides via onStop().
                    pause();
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Requests permission for devices with versions Marshmallow (M)/API 23 or above.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {

                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                        PERMISSION_CODE);

                return;
            }
        }

        // The following invoking method either executes for versions older than M, or until the
        // user accepts the in-app permission for the next sessions.
        runUI();
    }

    /**
     * Invoked either right after onCreate() or when the app is maximized back from being minimized.
     */
    @Override
    protected void onStart() {
        super.onStart();

        // Instantiates the Intent if it doesn't exist yet, binds to it, and then starts it.
        if (mPlayIntent == null) {
            Log.d(LOG_TAG, "onStart(): Binding and starting service");

            mPlayIntent = new Intent(this, MusicService.class);
            bindService(mPlayIntent, mMusicConnection, Context.BIND_AUTO_CREATE);
            startService(mPlayIntent);
        }
    }

    /**
     * Invoked either when the app is minimized or during an orientation change.
     */
    @Override
    protected void onStop() {
        Log.d(LOG_TAG, "onStop()");
        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.song_options, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        // Performs the following for the respective item.
        switch (item.getItemId()) {
            case R.id.option_now_playing:
                mSongListView.setSelection(songPosition); // Positions to 0 if less than 0
                break;
            case R.id.option_shuffle:
                if (!item.isChecked()) {
                    item.setChecked(true);
                    mShuffle = true;
                } else {
                    item.setChecked(false);
                    mShuffle = false;
                }
                break;
            case R.id.option_auto_repeat:
                if (!item.isChecked()) {
                    item.setChecked(true);
                    mAutoRepeat = true;
                } else {
                    item.setChecked(false);
                    mAutoRepeat = false;
                }
                break;
            case R.id.option_end:
                stopService(mPlayIntent);
                mMusicService = null;
                System.exit(0);
                break;
        }

        return super.onOptionsItemSelected(item);
    }

    // Displays a permission dialog when requested for devices M and above.
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode == PERMISSION_CODE) {

            // User accepts the permission(s).
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                // Invoker for rendering UI.
                runUI();
            } else { // User denies the permission.
                Toast.makeText(this, R.string.toast_grant_permissions, Toast.LENGTH_SHORT).show();

                // Runs a thread for a slight delay prior to shutting down the app.
                Thread mthread = new Thread() {
                    @Override
                    public void run() {
                        try {
                            sleep(1500);
                            System.exit(0);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                };

                mthread.start();
            }
        }
    }

    /**
     * Initializations/instantiations for UI.
     */
    private void runUI() {
        Log.d(LOG_TAG, "runUI()");

        // Initializes the progress bar.
        mProgressBar = (ProgressBar) findViewById(R.id.progress_bar);

        // Phone initialization and registration for the interface.
        TelephonyManager telephonyManager = (TelephonyManager)
                getSystemService(Context.TELEPHONY_SERVICE);
        telephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

        // ListView initialization.
        mSongListView = (ListView) findViewById(R.id.song_list);

        // Initializes and then sets the empty state TextView to the ListView for when it should be
        // empty.
        mEmptyStateTextView = (TextView) findViewById(R.id.empty_view);
        mEmptyStateTextView.setText(R.string.no_songs); // Initial state display.
        mSongListView.setEmptyView(mEmptyStateTextView);

        // Instantiates the following adapter that takes an empty array list as initial input.
        mSongAdapter = new SongAdapter(this, new ArrayList<Song>());

        // Sets the adapter on the list view so the list can be populated in the UI.
        mSongListView.setAdapter(mSongAdapter);

        // Registers the list view for a context menu of song options.
        registerForContextMenu(mSongListView);

        // Sets each song with a functionality.
        mSongListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long l) {
                Log.d(LOG_TAG, "Song item clicked");

                // Reassigns the current song position and updates the adapter's view.
                songPosition = position;
                mSongAdapter.notifyDataSetChanged();

                // Plays the respective song.
                mMusicService.playSong();

                // Sets the flag to false for the controller's duration and position purposes.
                if (mPlaybackPaused) mPlaybackPaused = false;
            }
        });

        // Sets the list view long-clickable with the following functionality.
        mSongListView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long l) {

                // Reassigns the current song position.
                mSongPositionOptions = position;

                // Opens up the context menu of song options.
                openContextMenu(mSongListView);

                return true;
            }
        });

        // Invokes the controller setup.
        setController();

        // Retrieves a reference to the LoaderManager in order to interact with loaders.
        LoaderManager loaderManager = getLoaderManager();

        // Passes the song loader ID to be used regardless of configuration change.
        loaderManager.initLoader(songLoaderID, null, this);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo
            menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        // References the current song that was long-clicked.
        Song song = mSongAdapter.getItem(mSongPositionOptions);

        // Sets the menu's title to the respective song's title.
        menu.setHeaderTitle(song.getTitle());

        // Adds the following menu options.
        menu.add(0, v.getId(), 0, "Delete");
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {

        // Runs code for the following menu option should the selected item match the title, and the
        // song currently playing not be attempted to delete. Otherwise, displays a Toast message.
        if (item.getTitle().equals("Delete") && mSongPositionOptions != songPosition) {

            // References the current song.
            final Song song = mSongAdapter.getItem(mSongPositionOptions);

            // Displays a dialog to confirm whether the user really wants to delete the song or not.
            new AlertDialog.Builder(this)
                    .setMessage("Are you sure you want to delete \"" + song.getTitle() + "\"?")
                    .setNegativeButton(android.R.string.no, null)
                    .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {

                        public void onClick(DialogInterface arg0, int arg1) {

                            // Decrements the position of the song currently playing if the deleted
                            // song is above it. Note that the current position wouldn't be
                            // impacted if the deleted song is below.
                            if (mSongPositionOptions < songPosition) songPosition--;

                            // Sets up the projection cursor-parameter (only the ID is required).
                            String[] projection = {MediaStore.Audio.Media._ID};

                            // Matches on the file path for the following cursor-parameters.
                            String selection = MediaStore.Audio.Media.DATA + " = ?";
                            String[] selectionArgs = new String[]{song.getPath()};

                            // Queries for the ID of the media matching the file path.
                            Uri musicUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                            ContentResolver musicResolver = getContentResolver();
                            Cursor musicCursor = musicResolver.query(
                                    musicUri,
                                    projection,
                                    selection,
                                    selectionArgs,
                                    null);

                            // Addresses the path's row in the database to delete via the content
                            // resolver which ultimately removes the song file. Otherwise, displays
                            // a Toast message.
                            if (musicCursor != null) {
                                if (musicCursor.moveToFirst()) {
                                    long id = musicCursor.getLong(
                                            musicCursor.getColumnIndexOrThrow(
                                                    MediaStore.Audio.Media._ID));
                                    Uri deleteUri = ContentUris.withAppendedId(
                                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                                    musicResolver.delete(deleteUri, null, null);

                                    Toast.makeText(
                                            getApplicationContext(),
                                            song.getTitle() + " deleted",
                                            Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(
                                            getApplicationContext(),
                                            R.string.toast_file_not_found,
                                            Toast.LENGTH_SHORT).show();
                                }

                                musicCursor.close();
                            }

                            // Increments the loader ID to eventually rerun the whole loader process
                            // to render an updated ListView.
                            songLoaderID++;
                            LoaderManager loaderManager = getLoaderManager();
                            loaderManager.initLoader(songLoaderID, null, MainActivity.this);
                        }
                    }).create().show();
        } else if (item.getTitle().equals("Delete") && mSongPositionOptions == songPosition) {
            Toast.makeText(this, R.string.toast_song_curr_playing, Toast.LENGTH_SHORT).show();
        }

        return true;
    }

    /**
     * Shows the controller accordingly.
     */
    public static void showController() {
        Log.d(LOG_TAG, "showController()");

        mController.show(0);
    }

    // Connects to the service to bind the interaction between the Service class and the Activity.
    private ServiceConnection mMusicConnection = new ServiceConnection(){

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(LOG_TAG, "onServiceConnected()");

            MusicBinder binder = (MusicBinder) service;

            // Gets service.
            mMusicService = binder.getService();

            // Sets the flag to true and invokes a setter method for setting up the song list,
            // respectively.
            mMusicBound = true;
            setSongList();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(LOG_TAG, "onServiceDisconnected()");

            mMusicBound = false;
        }
    };

    /**
     * Sets the controller up.
     */
    private void setController() {
        Log.d(LOG_TAG, "setController()");

        mController = new MusicController(this);

        // Addresses when the user presses the previous/next buttons.
        mController.setPrevNextListeners(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playNext();
            }
        }, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playPrevious();
            }
        });

        // Sets the controller to work on media playback in the app, with its anchor view referring
        // to the song list.
        mController.setMediaPlayer(this);
        mController.setAnchorView(findViewById(R.id.song_list));
        mController.setEnabled(true);
    }

    /**
     * Plays the next song via the Service class.
     */
    private void playNext(){
        mMusicService.playNext();

        // Sets the flag to false for the controller's duration and position purposes.
        if (mPlaybackPaused) mPlaybackPaused = false;

        // Updates the adapter's views.
        mSongAdapter.notifyDataSetChanged();
    }

    /**
     * Plays the previous song via the Service class.
     */
    private void playPrevious(){
        mMusicService.playPrevious();

        // Sets the flag to false for the controller's duration and position purposes.
        if (mPlaybackPaused) mPlaybackPaused = false;

        // Updates the adapter's views.
        mSongAdapter.notifyDataSetChanged();
    }

    // The following are MediaPlayerControl interface methods.
    @Override
    public void start() {
        Log.d(LOG_TAG, "start()");

        // Sets the pause flag back to false and then updates the adapter's view.
        mPlaybackPaused = false;
        mSongAdapter.notifyDataSetChanged();

        // Executes when the user resumes the paused song.
        mMusicService.go();
    }

    @Override
    public void pause() {
        Log.d(LOG_TAG, "pause()");

        // Sets the pause flag to true and then updates the adapter's view.
        mPlaybackPaused = true;
        mSongAdapter.notifyDataSetChanged();

        // Executes when the user pauses the current song.
        mMusicService.pausePlayer();
    }

    /**
     * Getter interface method for the song's total length.
     */
    @Override
    public int getDuration() {
        Log.d(LOG_TAG, "getDuration()");

        // Returns the song's current duration as it is currently playing. Otherwise, returns 0
        // with the exception of it being paused (so return its duration).
        if (mMusicService != null && mMusicBound && mMusicService.isPlaying()) {
            return mMusicService.getDuration();
        } else {
            if (mPlaybackPaused) return mMusicService.getDuration();

            return 0;
        }
    }

    /**
     * Getter interface method for the song's current position at the minute-mark.
     */
    @Override
    public int getCurrentPosition() {
        Log.d(LOG_TAG, "getCurrentPosition()");

        // Returns the song's current position as it is currently playing. Otherwise, returns 0
        // with the exception of it being paused (so return its position).
        if (mMusicService != null && mMusicBound && mMusicService.isPlaying()) {
            return mMusicService.getPosition();
        } else {
            if (mPlaybackPaused) return mMusicService.getPosition();

            return 0;
        }
    }

    @Override
    public void seekTo(int position) {
        mMusicService.seek(position);
    }

    @Override
    public boolean isPlaying() {
        Log.d(LOG_TAG, "isPlaying()");

        if (mMusicService != null && mMusicBound) return mMusicService.isPlaying();

        return false;
    }

    @Override
    public int getBufferPercentage() {
        return 0;
    }

    @Override
    public boolean canPause() {
        return true;
    }

    @Override
    public boolean canSeekBackward() {
        return true;
    }

    @Override
    public boolean canSeekForward() {
        return true;
    }

    @Override
    public int getAudioSessionId() {
        return 0;
    }

    @Override
    public Loader<List<Song>> onCreateLoader(int i, Bundle bundle) {
        Log.d(LOG_TAG, "onCreateLoader()");

        // Displays the progress bar while running the loader.
        mProgressBar.setVisibility(View.VISIBLE);

        return new SongLoader(this);
    }

    @Override
    public void onLoadFinished(Loader<List<Song>> loader, List<Song> songs) {

        // Clears the adapter of previous song data.
        mSongAdapter.clear();

        // Sorts the data so that the song titles are presented alphabetically.
        Collections.sort(songs, new Comparator<Song>(){
            public int compare(Song a, Song b){
                return a.getTitle().compareTo(b.getTitle());
            }
        });

        // Runs the following should the song list not be null nor empty.
        if (songs != null && !songs.isEmpty()) {

            // Reassigns the value of the songs list field.
            mSongList = songs;

            // Sets the flag to true and invokes setting up the song list, respectively.
            mLoadFinished = true;
            setSongList();

            // Adds the list of Songs to the adapter's dataset.
            mSongAdapter.addAll(songs);
        }

        // Hides the progress bar after the loader finishes.
        mProgressBar.setVisibility(View.INVISIBLE);

        // Views the current song in-focus - positions to 0, the first row, if songPosition is
        // negative. This is particularly useful when the app is maximized back into session.
        mSongListView.setSelection(songPosition);

        Log.d(LOG_TAG, "onLoadFinished()");
    }

    /**
     * Invoked when the app closes.
     *
     * @param loader is the passed-in loader that could be addressed.
     */
    @Override
    public void onLoaderReset(Loader<List<Song>> loader) {
        Log.d(LOG_TAG, "onLoaderReset()");

        // Clears out the existing data since the loader resetted.
        mSongAdapter.clear();
    }

    /**
     * Setter method for the song list ONLY when both the service is bound AND the loader finishing
     * up the load.
     */
    private void setSongList() {
        if (mMusicBound && mLoadFinished) {
            Log.d(LOG_TAG, "Setting up song list");

            mMusicService.setList(mSongList);
        }
    }

    /**
     * Invoked when the user presses the navigation key, back button.
     */
    @Override
    public void onBackPressed() {
        Log.d(LOG_TAG, "onBackPressed()");

        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.dialog_quit_app_confirm)
                .setNegativeButton(android.R.string.no, null)
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {

                    public void onClick(DialogInterface arg0, int arg1) {

                        // Force-closes the app.
                        System.exit(0);
                    }
                }).create().show();
    }
}
