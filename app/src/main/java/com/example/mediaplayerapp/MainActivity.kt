package com.example.mediaplayerapp
import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    // UI Components
    private lateinit var tvCurrentSong: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPrevious: Button
    private lateinit var btnPlay: Button
    private lateinit var btnNext: Button
    private lateinit var songListView: ListView

    // Media Player Components
    private val mediaPlayerLock = Any()
    private var mediaPlayer: MediaPlayer? = null
    private var songsList = mutableListOf<Song>()
    private var currentSongIndex = 0
    private var isPlaying = false

    // Handler for seek bar updates
    private val handler = Handler(Looper.getMainLooper())

    // Song data class
    data class Song(
        val title: String,
        val path: String,
        val duration: Long
    )

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1
        private const val TAG = "MediaPlayerApp"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components
        initializeComponents()

        // Setup button listeners
        setupButtonListeners()

        // Check and request storage permissions
        checkStoragePermission()
    }

    private fun initializeComponents() {
        tvCurrentSong = findViewById(R.id.tvCurrentSong)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        seekBar = findViewById(R.id.seekBar)
        btnPrevious = findViewById(R.id.btnPrevious)
        btnPlay = findViewById(R.id.btnPlay)
        btnNext = findViewById(R.id.btnNext)
        songListView = findViewById(R.id.songListView)
    }

    private fun setupButtonListeners() {
        btnPlay.setOnClickListener {
            if (songsList.isEmpty()) {
                Toast.makeText(this, "No songs available", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            synchronized(mediaPlayerLock) {
                when {
                    mediaPlayer == null -> playSong()
                    isPlaying -> pauseMusic()
                    else -> resumeMusic()
                }
            }
        }

        btnNext.setOnClickListener {
            playNextSong()
        }

        btnPrevious.setOnClickListener {
            playPreviousSong()
        }

        // Seek bar listener
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                synchronized(mediaPlayerLock) {
                    if (fromUser && mediaPlayer != null) {
                        try {
                            mediaPlayer?.seekTo(progress)
                            updateCurrentTimeText(progress)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error seeking in track", e)
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun checkStoragePermission() {
        val permissions = mutableListOf<String>()

        // Check for audio permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            // For older versions, check external storage permission
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        } else {
            // Permissions are granted, load music files
            loadMusicFiles()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                loadMusicFiles()
            } else {
                Toast.makeText(this, "Storage Permission Denied", Toast.LENGTH_SHORT).show()
                // Try to load from raw resources as fallback
                loadFromRawResources()
            }
        }
    }

    private fun loadMusicFiles() {
        Thread {
            try {
                songsList.clear()

                // Try to load from device storage first
                val externalSongs = loadFromDeviceStorage()

                if (externalSongs.isEmpty()) {
                    // If no external songs, try raw resources
                    Log.d(TAG, "No external songs found, trying raw resources")
                    loadFromRawResources()
                } else {
                    // Add external songs to the list
                    songsList.addAll(externalSongs)

                    runOnUiThread {
                        Log.d(TAG, "Total external songs found: ${songsList.size}")
                        setupSongList()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Critical error in music loading", e)
                runOnUiThread {
                    Toast.makeText(this, "Error accessing music files", Toast.LENGTH_LONG).show()
                    // Try raw resources as last resort
                    loadFromRawResources()
                }
            }
        }.start()
    }

    private fun loadFromDeviceStorage(): List<Song> {
        val tempSongsList = mutableListOf<Song>()

        // Multiple URIs to try
        val uris = listOf(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.INTERNAL_CONTENT_URI
        )

        for (uri in uris) {
            Log.d(TAG, "Trying URI: $uri")

            val projection = arrayOf(
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.IS_MUSIC
            )

            // More flexible selection
            val selection = "(${MediaStore.Audio.Media.IS_MUSIC} = 1 OR ${MediaStore.Audio.Media.IS_MUSIC} = -1)"

            val cursor = contentResolver.query(
                uri,
                projection,
                selection,
                null,
                MediaStore.Audio.Media.TITLE
            )

            cursor?.use {
                Log.d(TAG, "Cursor count for $uri: ${it.count}")

                val titleColumnIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val pathColumnIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val durationColumnIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val isMusicColumnIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_MUSIC)

                while (it.moveToNext()) {
                    try {
                        val title = it.getString(titleColumnIndex) ?: "Unknown"
                        val path = it.getString(pathColumnIndex) ?: continue
                        val duration = it.getLong(durationColumnIndex)
                        val isMusic = it.getInt(isMusicColumnIndex)

                        Log.d(TAG, "Potential song: Title=$title, Path=$path, Duration=$duration, IsMusic=$isMusic")

                        val file = File(path)
                        if (path.lowercase().endsWith(".mp3") && file.exists() && file.canRead()) {
                            tempSongsList.add(Song(title, path, duration))
                            Log.d(TAG, "Added song: $title")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing song", e)
                    }
                }
            } ?: Log.e(TAG, "Cursor is null for URI: $uri")
        }

        return tempSongsList
    }

    private fun loadFromRawResources() {
        Log.d(TAG, "Loading from raw resources")

        // List of raw resource IDs
        val rawResources = listOf(
            R.raw.sample,  // Make sure these resources exist in your project
            R.raw.sample1
        )

        songsList.clear()

        for (resourceId in rawResources) {
            try {
                val mediaPlayer = MediaPlayer.create(this, resourceId)
                val title = resources.getResourceEntryName(resourceId)
                val duration = mediaPlayer.duration.toLong()

                songsList.add(Song(title, "raw://$resourceId", duration))
                Log.d(TAG, "Added raw resource song: $title with duration $duration")

                // Release the temporary media player
                mediaPlayer.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading raw resource", e)
            }
        }

        runOnUiThread {
            if (songsList.isEmpty()) {
                Toast.makeText(this, "No music files found in raw resources", Toast.LENGTH_LONG).show()
            } else {
                Log.d(TAG, "Total raw resource songs: ${songsList.size}")
                setupSongList()
            }
        }
    }

    private fun setupSongList() {
        val songNames = songsList.map { it.title }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, songNames)
        songListView.adapter = adapter

        songListView.setOnItemClickListener { _, _, position, _ ->
            currentSongIndex = position
            playSong()
        }

        // Automatically select first song if available
        if (songsList.isNotEmpty()) {
            currentSongIndex = 0
            updateUIForSong()
        }
    }

    private fun playSong() {
        if (songsList.isEmpty()) {
            Toast.makeText(this, "No songs available", Toast.LENGTH_SHORT).show()
            return
        }

        // Release previous media player
        synchronized(mediaPlayerLock) {
            mediaPlayer?.apply {
                try {
                    if (isPlaying) stop()
                    reset()
                    release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing previous player", e)
                }
            }
            mediaPlayer = null
        }

        Thread {
            try {
                val currentSong = songsList[currentSongIndex]

                // Create and initialize the media player based on source type
                val player = if (currentSong.path.startsWith("raw://")) {
                    // For raw resources
                    val resourceId = currentSong.path.substringAfter("raw://").toInt()
                    MediaPlayer.create(this, resourceId).apply {
                        setVolume(0.5f, 0.5f)
                    }
                } else {
                    // For file system
                    val file = File(currentSong.path)

                    // Validate file
                    if (!file.exists()) throw IOException("Song file does not exist")
                    if (!file.canRead()) throw IOException("Cannot read song file")
                    if (file.length() <= 0) throw IOException("Song file is empty")

                    MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setVolume(0.5f, 0.5f)
                        prepare()
                    }
                }

                runOnUiThread {
                    synchronized(mediaPlayerLock) {
                        mediaPlayer = player
                        try {
                            player.start()
                            isPlaying = true
                            btnPlay.text = "Pause"
                            updateUIForSong()
                            setupSeekBar()

                            player.setOnCompletionListener {
                                runOnUiThread {
                                    playNextSong()
                                }
                            }

                            player.setOnErrorListener { mp, what, extra ->
                                Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                                runOnUiThread {
                                    Toast.makeText(
                                        this,
                                        "Media playback error",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                true
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting playback", e)
                            Toast.makeText(
                                this,
                                "Error playing song: ${e.localizedMessage}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    val errorMessage = when (e) {
                        is SecurityException -> "Cannot access song file"
                        is IOException -> "Error preparing song: ${e.message}"
                        else -> "Unexpected error playing song: ${e.message}"
                    }
                    Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()
                    Log.e(TAG, errorMessage, e)
                }
            }
        }.start()
    }

    private fun playNextSong() {
        if (songsList.isEmpty()) return
        currentSongIndex = (currentSongIndex + 1) % songsList.size
        playSong()
    }

    private fun playPreviousSong() {
        if (songsList.isEmpty()) return
        currentSongIndex = (currentSongIndex - 1 + songsList.size) % songsList.size
        playSong()
    }

    private fun pauseMusic() {
        synchronized(mediaPlayerLock) {
            mediaPlayer?.let { player ->
                try {
                    player.pause()
                    isPlaying = false
                    btnPlay.text = "Play"
                    handler.removeCallbacksAndMessages(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Error pausing music", e)
                }
            }
        }
    }

    private fun resumeMusic() {
        synchronized(mediaPlayerLock) {
            mediaPlayer?.let { player ->
                try {
                    player.start()
                    isPlaying = true
                    btnPlay.text = "Pause"
                    setupSeekBar()
                } catch (e: Exception) {
                    Log.e(TAG, "Error resuming music", e)
                    Toast.makeText(this, "Error resuming music", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateUIForSong() {
        val currentSong = songsList[currentSongIndex]
        tvCurrentSong.text = currentSong.title
        tvTotalTime.text = formatTime(currentSong.duration)
    }

    private fun setupSeekBar() {
        mediaPlayer?.let { player ->
            seekBar.max = player.duration

            handler.postDelayed(object : Runnable {
                override fun run() {
                    try {
                        if (isPlaying && mediaPlayer != null) {
                            val currentPosition = player.currentPosition
                            seekBar.progress = currentPosition
                            updateCurrentTimeText(currentPosition)
                            handler.postDelayed(this, 1000)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating seekbar", e)
                    }
                }
            }, 1000)
        }
    }

    private fun updateCurrentTimeText(currentPosition: Int) {
        tvCurrentTime.text = formatTime(currentPosition.toLong())
    }

    // Utility function to format time
    private fun formatTime(millis: Long): String {
        return String.format(
            "%02d:%02d",
            TimeUnit.MILLISECONDS.toMinutes(millis),
            TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        synchronized(mediaPlayerLock) {
            mediaPlayer?.apply {
                try {
                    if (isPlaying) stop()
                    release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onDestroy", e)
                }
                mediaPlayer = null
            }
            handler.removeCallbacksAndMessages(null)
        }
    }
}