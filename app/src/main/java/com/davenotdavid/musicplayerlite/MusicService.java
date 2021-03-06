package com.davenotdavid.musicplayerlite;

import android.app.Service;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.IBinder;
import android.content.ContentUris;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.support.v7.app.AlertDialog;
import android.util.Log;

import java.util.List;
import java.util.Random;

import static com.davenotdavid.musicplayerlite.MainActivity.mAutoRepeat;
import static com.davenotdavid.musicplayerlite.MainActivity.mShuffle;
import static com.davenotdavid.musicplayerlite.MainActivity.mSongAdapter;
import static com.davenotdavid.musicplayerlite.MainActivity.showController;
import static com.davenotdavid.musicplayerlite.MainActivity.songPosition;

/**
 * A subclass of {@link Service} that assists with executing music playback continuously even when
 * the app is minimized.
 */
public class MusicService extends Service implements MediaPlayer.OnPreparedListener,
        MediaPlayer.OnErrorListener, MediaPlayer.OnCompletionListener {

    // Log tag constant.
    private static final String LOG_TAG = MusicService.class.getSimpleName();

    // MediaPlayer field.
    private MediaPlayer mPlayer;

    // Song list field.
    private List<Song> mSongList;

    // Initialization used to assist with the binding process.
    private final IBinder mMusicBinder = new MusicBinder();

    // Random field used to assist with implementing shuffle functionality.
    private Random mRandom;

    @Override
    public void onCreate(){
        super.onCreate();

        Log.d(LOG_TAG, "MusicService: onCreate()"); // Gets invoked once at most

        // Initializations.
        initMusicPlayer();
        mRandom = new Random();
    }

    /**
     * Initializing method for the MediaPlayer.
     */
    public void initMusicPlayer(){
        mPlayer = new MediaPlayer();

        // Sets the stream type to music.
        mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);

        // Sets the following to their respective listener.
        mPlayer.setOnPreparedListener(this); // When the MediaPlayer instance is prepared.
        mPlayer.setOnCompletionListener(this); // When a song has completed playback.
        mPlayer.setOnErrorListener(this); // When an error is thrown.
    }

    /**
     * Setter method for retrieving the song list from the Activity.
     *
     * @param songs is the list of songs.
     */
    public void setList(List<Song> songs){
        mSongList = songs;
    }

    /**
     * Assists with the interaction between the Activity and this Service class.
     */
    public class MusicBinder extends Binder {
        MusicService getService() {
            return MusicService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(LOG_TAG, "onBind()");

        return mMusicBinder;
    }

    @Override
    public boolean onUnbind(Intent intent){
        Log.d(LOG_TAG, "onUnbind()");

        // Releases MediaPlayer resources when the Service is unbound (e.g. user closing app).
        mPlayer.stop();
        mPlayer.release();
        return false;
    }

    /**
     * Plays a song from the song list.
     */
    public void playSong(){
        mPlayer.reset(); // Used also when the user plays songs progressively.

        // Retrieves the respective song.
        Song song = mSongList.get(songPosition);

        // Retrieves the song's ID.
        long currentSong = song.getID();

        // Sets up the URI.
        Uri trackUri = ContentUris.withAppendedId(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                currentSong);

        // Tries setting up the URI as the data source for the MediaPlayer.
        try {
            mPlayer.setDataSource(getApplicationContext(), trackUri);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error setting data source.", e);
        }

        // Tries and prepares its asynchronous task. Otherwise, a dialog is displayed when an
        // IllegalStateException is caught.
        try {
            mPlayer.prepareAsync();
        } catch (IllegalStateException e) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_song_error_title)
                    .setMessage("\"" + song.getTitle() + "\" could not be played")
                    .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface arg0, int arg1) {}
                    }).create().show();
        }
    }

    @Override
    public void onPrepared(MediaPlayer mediaPlayer) {
        Log.d(LOG_TAG, "onPrepared()");

        mediaPlayer.start(); // Begins playback

        showController(); // Updates the controller accordingly
    }

    @Override
    public boolean onError(MediaPlayer mediaPlayer, int i, int i1) {
        Log.d(LOG_TAG, "onError()");

        mediaPlayer.reset();

        return false;
    }

    // Invoked when a song is complete.
    @Override
    public void onCompletion(MediaPlayer mediaPlayer) {
        if (mPlayer.getCurrentPosition() > 0){ // Greater than the 0 millisecond mark
            mediaPlayer.reset();

            // Repeats the song (re-initializes mPlayer by setting the data source prior to
            // preparing the task) should the auto-repeat option be checked. Otherwise, plays the
            // next song.
            if (mAutoRepeat) playSong();
            else playNext();
        }

        // Updates the adapter's view accordingly.
        mSongAdapter.notifyDataSetChanged();
    }

    // The following methods all apply to standard playback control functions that the user will
    // expect.
    public int getPosition(){
        return mPlayer.getCurrentPosition();
    }

    public int getDuration(){
        return mPlayer.getDuration();
    }

    public boolean isPlaying(){
        return mPlayer.isPlaying();
    }

    public void pausePlayer(){
        mPlayer.pause();
    }

    public void seek(int position){
        mPlayer.seekTo(position);
    }

    public void go(){
        mPlayer.start();
    }

    /**
     * Runs the following code for when the previous song is played.
     */
    public void playPrevious(){
        songPosition--;
        if (songPosition < 0) songPosition = mSongList.size() - 1;
        playSong();
    }

    /**
     * Runs the following code for when the next song is played. Shuffles by retrieving a random
     * song from the list should the boolean flag be true.
     */
    public void playNext(){
        if (mShuffle){
            int newSong = songPosition;
            while (newSong == songPosition){ // Loops until false so guaranteed random
                newSong = mRandom.nextInt(mSongList.size());
            }
            songPosition = newSong;
        } else {
            songPosition++;
            if (songPosition >= mSongList.size()) songPosition = 0;
        }

        playSong();
    }
}
