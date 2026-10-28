package com.lnu.volumelockr.plus.ui

import android.content.Context
import android.content.res.ColorStateList
import android.media.AudioManager
import android.os.Build
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import com.lnu.volumelockr.plus.R
import com.lnu.volumelockr.plus.databinding.VolumeCardBinding
import com.lnu.volumelockr.plus.service.VolumeService
import com.lnu.volumelockr.plus.util.SecurityUtils
import com.google.android.material.R as MaterialR

class VolumeAdapter(
    private var mVolumeList: List<Volume>,
    private var mService: VolumeService?,
    private var mContext: Context,
    var onLockStateChanged: (() -> Unit)? = null,
    private val tvMode: Boolean = false
) :
    RecyclerView.Adapter<VolumeAdapter.ViewHolder>() {

    companion object {
        private var lastDndPromptTime = 0L
        private val STREAM_ICONS = mapOf(
            AudioManager.STREAM_MUSIC to R.drawable.ic_media,
            AudioManager.STREAM_VOICE_CALL to R.drawable.ic_call,
            AudioManager.STREAM_NOTIFICATION to R.drawable.ic_bell,
            AudioManager.STREAM_RING to R.drawable.ic_bell,
            AudioManager.STREAM_ALARM to R.drawable.ic_alarm
        )

        private val STREAM_CONTAINER_COLORS = mapOf(
            AudioManager.STREAM_MUSIC to MaterialR.attr.colorPrimaryContainer,
            AudioManager.STREAM_RING to MaterialR.attr.colorSecondaryContainer,
            AudioManager.STREAM_VOICE_CALL to MaterialR.attr.colorTertiaryContainer,
            AudioManager.STREAM_NOTIFICATION to MaterialR.attr.colorSecondaryContainer,
            AudioManager.STREAM_ALARM to MaterialR.attr.colorErrorContainer
        )

        private val STREAM_ON_CONTAINER_COLORS = mapOf(
            AudioManager.STREAM_MUSIC to MaterialR.attr.colorOnPrimaryContainer,
            AudioManager.STREAM_RING to MaterialR.attr.colorOnSecondaryContainer,
            AudioManager.STREAM_VOICE_CALL to MaterialR.attr.colorOnTertiaryContainer,
            AudioManager.STREAM_NOTIFICATION to MaterialR.attr.colorOnSecondaryContainer,
            AudioManager.STREAM_ALARM to MaterialR.attr.colorOnErrorContainer
        )
    }

    private var activeDraggingStream: Int? = null

    private var mAudioManager: AudioManager =
        mContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @MainThread
    fun update(volumes: List<Volume>) {
        mVolumeList = volumes
        if (activeDraggingStream == null) {
            notifyDataSetChanged()
        }
    }

    @MainThread
    fun update() {
        if (activeDraggingStream == null) {
            notifyDataSetChanged()
        }
    }

    inner class ViewHolder(val binding: VolumeCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = VolumeCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val volume = mVolumeList[position]
        val isLocked = mService?.getLocks()?.containsKey(volume.stream) == true
        holder.binding.mediaTextView.text = volume.name
        holder.binding.streamIcon.setImageResource(STREAM_ICONS[volume.stream] ?: R.drawable.ic_media)
        applyStreamColors(holder, volume.stream, isLocked)
        val currentLockVal = mService?.getLocks()?.get(volume.stream)?.toFloat()
        val displayVal = currentLockVal ?: volume.value.toFloat()
        if (activeDraggingStream != volume.stream) {
            holder.binding.slider.valueFrom = volume.min.toFloat()
            holder.binding.slider.valueTo = volume.max.toFloat()
            holder.binding.slider.value = displayVal.coerceIn(volume.min.toFloat(), volume.max.toFloat())
            holder.binding.volumeValue.text = formatVolumeValue(holder.binding.slider.value.toInt(), volume.max)
        }

        holder.binding.volumeValue.setOnClickListener {
            showDirectVolumeInputDialog(holder, volume)
        }

        registerSeekBarCallback(holder, volume)
        registerLockButtonCallback(holder, volume)

        loadLockFromService(holder, volume)

        handleRingerMode(holder, volume)

        if (isLocked) {
            holder.binding.slider.isEnabled = false
        }
        
        val uiModeManager = mContext.getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager
        val isTv = uiModeManager.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val hideUnlock = PreferenceManager.getDefaultSharedPreferences(mContext).getBoolean("hide_tv_unlock_ui", true)
        
        if (tvMode || (isTv && hideUnlock)) {
            holder.binding.lockButton.visibility = android.view.View.GONE
        } else {
            holder.binding.lockButton.visibility = android.view.View.VISIBLE
        }

        setupFocusAnimations(holder)
    }

    private fun setupFocusAnimations(holder: ViewHolder) {
        val focusChangeListener = android.view.View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.animate().scaleX(1.05f).scaleY(1.05f).translationZ(8f).setDuration(150).start()
            } else {
                view.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(150).start()
            }
        }
        holder.binding.slider.onFocusChangeListener = focusChangeListener
        holder.binding.lockButton.onFocusChangeListener = focusChangeListener
    }

    private fun hasDndAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nm = mContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.isNotificationPolicyAccessGranted
        } else {
            true
        }
    }

    private fun isDndRestricted(stream: Int): Boolean {
        return (stream == AudioManager.STREAM_RING || stream == AudioManager.STREAM_NOTIFICATION) && !hasDndAccess()
    }

    private fun applyStreamColors(holder: ViewHolder, stream: Int, isLocked: Boolean) {
        val containerColor: Int
        val onContainerColor: Int
        val thumbColor: Int
        val trackActiveColor: Int
        val trackInactiveColor: Int

        if (isDndRestricted(stream)) {
            containerColor = ContextCompat.getColor(mContext, R.color.black_dnd_container)
            onContainerColor = ContextCompat.getColor(mContext, R.color.black_dnd_on_container)
            thumbColor = containerColor
            trackActiveColor = containerColor
            trackInactiveColor = containerColor
        } else {
            if (isLocked) {
                containerColor = ContextCompat.getColor(mContext, R.color.locked_color_container)
                onContainerColor = ContextCompat.getColor(mContext, R.color.locked_color_on_container)
            } else if (stream == AudioManager.STREAM_VOICE_CALL) {
                containerColor = ContextCompat.getColor(mContext, R.color.call_color_container)
                onContainerColor = ContextCompat.getColor(mContext, R.color.call_color_on_container)
            } else if (stream == AudioManager.STREAM_MUSIC) {
                containerColor = ContextCompat.getColor(mContext, R.color.media_color_container)
                onContainerColor = ContextCompat.getColor(mContext, R.color.media_color_on_container)
            } else if (stream == AudioManager.STREAM_RING || stream == AudioManager.STREAM_NOTIFICATION) {
                containerColor = ContextCompat.getColor(mContext, R.color.ring_color_container)
                onContainerColor = ContextCompat.getColor(mContext, R.color.ring_color_on_container)
            } else {
                val containerAttr = STREAM_CONTAINER_COLORS[stream] ?: MaterialR.attr.colorPrimaryContainer
                val onContainerAttr = STREAM_ON_CONTAINER_COLORS[stream] ?: MaterialR.attr.colorOnPrimaryContainer
                containerColor = MaterialColors.getColor(holder.binding.root, containerAttr)
                onContainerColor = MaterialColors.getColor(holder.binding.root, onContainerAttr)
            }
            thumbColor = onContainerColor
            trackActiveColor = onContainerColor
            trackInactiveColor = containerColor
        }

        with(holder.binding) {
            iconContainer.backgroundTintList = ColorStateList.valueOf(containerColor)
            streamIcon.imageTintList = ColorStateList.valueOf(onContainerColor)

            slider.thumbTintList = ColorStateList.valueOf(thumbColor)
            slider.trackActiveTintList = ColorStateList.valueOf(trackActiveColor)
            slider.trackInactiveTintList = ColorStateList.valueOf(trackInactiveColor)

            val alphaVal = if (isLocked) 0.5f else 1.0f
            mediaTextView.alpha = alphaVal
            volumeValue.alpha = alphaVal
        }
    }

    private fun formatVolumeValue(value: Int, max: Int): String = "$value / $max"

    private fun registerSeekBarCallback(holder: ViewHolder, volume: Volume) {
        holder.binding.slider.clearOnChangeListeners()
        holder.binding.slider.clearOnSliderTouchListeners()

        holder.binding.slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                slider.parent?.requestDisallowInterceptTouchEvent(true)
                activeDraggingStream = volume.stream
            }

            override fun onStopTrackingTouch(slider: Slider) {
                activeDraggingStream = null
                slider.parent?.requestDisallowInterceptTouchEvent(false)
            }
        })

        holder.binding.slider.addOnChangeListener(
            Slider.OnChangeListener { slider, value, fromUser ->
                if (!fromUser) return@OnChangeListener

                if (isDndRestricted(volume.stream)) {
                    val now = System.currentTimeMillis()
                    if (now - lastDndPromptTime > 3000) {
                        lastDndPromptTime = now
                        android.widget.Toast.makeText(holder.binding.root.context, holder.binding.root.context.getString(R.string.toast_grant_dnd), android.widget.Toast.LENGTH_LONG).show()
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                            holder.binding.root.context.startActivity(intent)
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                    slider.value = mAudioManager.getStreamVolume(volume.stream).toFloat()
                    return@OnChangeListener
                }

                try {
                    mAudioManager.setStreamVolume(volume.stream, value.toInt(), 0)
                } catch (e: SecurityException) {
                    // Ignore if user removed DND permissions
                }

                volume.value = value.toInt()
                holder.binding.volumeValue.text = formatVolumeValue(value.toInt(), volume.max)
            }
        )
    }

    private fun registerLockButtonCallback(holder: ViewHolder, volume: Volume) {
        holder.binding.lockButton.setOnClickListener {
            val isLocked = mService?.getLocks()?.containsKey(volume.stream) == true
            if (isLocked) {
                onVolumeUnlocked(holder, volume)
            } else {
                onVolumeLocked(holder, volume)
            }
        }
    }

    private fun loadLockFromService(holder: ViewHolder, volume: Volume) {
        val isLocked = mService?.getLocks()?.containsKey(volume.stream) == true
        applyLockedState(holder, volume, isLocked)
        holder.binding.slider.isEnabled = !isLocked
    }

    private fun applyLockedState(holder: ViewHolder, volume: Volume, isLocked: Boolean) {
        val iconRes = if (isLocked) R.drawable.ic_lock else R.drawable.ic_lock_open
        val tintColor = if (isLocked) {
            ContextCompat.getColor(mContext, R.color.locked_color_on_container)
        } else {
            MaterialColors.getColor(holder.binding.root, MaterialR.attr.colorOnSurfaceVariant)
        }

        holder.binding.lockButton.icon = ContextCompat.getDrawable(mContext, iconRes)
        holder.binding.lockButton.iconTint = ColorStateList.valueOf(tintColor)

        applyStreamColors(holder, volume.stream, isLocked)
    }

    private fun adjustService() {
        mService?.getLocks()?.let {
            if (it.isNotEmpty()) {
                mService?.startLocking()
            } else {
                mService?.stopLocking()
            }
        }
    }

    private fun adjustNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mService?.getLocks()?.let {
                if (it.isNotEmpty()) {
                    mService?.tryShowNotification()
                } else {
                    mService?.tryHideNotification()
                }
            }
        }
    }

    private fun handleRingerMode(holder: ViewHolder, volume: Volume) {
        if (volume.stream == AudioManager.STREAM_NOTIFICATION || volume.stream == AudioManager.STREAM_RING) {
            holder.binding.slider.isEnabled = mService?.getLocks()?.containsKey(volume.stream) == false
        }
    }

    private fun onVolumeLocked(holder: ViewHolder, volume: Volume) {
        mService?.let {
            it.addLock(volume.stream, volume.value)
            VolumeService.start(mContext)
            adjustService()
            adjustNotification()
            holder.binding.slider.isEnabled = false
            applyLockedState(holder, volume, true)
            onLockStateChanged?.invoke()
        }
    }

    private fun onVolumeUnlocked(holder: ViewHolder, volume: Volume) {
        mService?.let {
            it.removeLock(volume.stream)
            adjustService()
            adjustNotification()
            holder.binding.slider.isEnabled = true
            applyLockedState(holder, volume, false)
            onLockStateChanged?.invoke()
        }
    }

    private fun showDirectVolumeInputDialog(holder: ViewHolder, volume: Volume) {
        val context = holder.binding.root.context
        val min = volume.min
        val max = volume.max
        val current = holder.binding.slider.value.toInt()

        val input = com.google.android.material.textfield.TextInputEditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            setSelection(text?.length ?: 0)
        }
        val container = android.widget.FrameLayout(context).apply {
            val padding = (24 * context.resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.set_custom_volume))
            .setMessage(context.getString(R.string.enter_volume_value, min, max))
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val entered = input.text.toString().toIntOrNull()
                if (entered != null) {
                    val clamped = entered.coerceIn(min, max)
                    if (isDndRestricted(volume.stream)) {
                        android.widget.Toast.makeText(context, context.getString(R.string.toast_grant_dnd), android.widget.Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }

                    try {
                        mAudioManager.setStreamVolume(volume.stream, clamped, 0)
                    } catch (e: SecurityException) {
                        // Ignore
                    }

                    volume.value = clamped
                    holder.binding.slider.value = clamped.toFloat()
                    holder.binding.volumeValue.text = formatVolumeValue(clamped, volume.max)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun isPasswordProtected(): Boolean {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext)
        return sharedPreferences.getBoolean(SettingsFragment.PASSWORD_PROTECTED_PREFERENCE, false)
    }

    override fun getItemCount(): Int {
        return mVolumeList.size
    }
}

data class Volume(
    val name: String,
    val stream: Int,
    var value: Int,
    val min: Int,
    val max: Int,
    var locked: Boolean
)
