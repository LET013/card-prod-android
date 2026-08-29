package com.xingyao.card.core.tts;

import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Log;

import java.util.Locale;
import java.util.Set;

/** Native speech capability. Vue owns the decision of which business prompt to play. */
public final class TtsManager {
    private static final String TAG = "TtsManager";
    private static final int MAX_TEXT_LENGTH = 200;
    private static final long PENDING_TTL_MS = 5_000L;

    private final Object lock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Context context;
    private TextToSpeech engine;
    private PendingSpeech pendingSpeech;
    private boolean initialized;
    private boolean ready;
    private boolean closed;
    private String voiceMode = "INITIALIZING";

    public TtsManager(Context context) {
        this.context = context.getApplicationContext();
        mainHandler.post(this::initializeOnMainThread);
    }

    public SpeakStatus speak(String text, boolean flush) {
        String normalized = normalizeText(text);
        if (normalized.isEmpty()) return SpeakStatus.rejected("TEXT_REQUIRED", voiceMode);

        synchronized (lock) {
            if (closed) return SpeakStatus.rejected("TTS_CLOSED", voiceMode);
            if (initialized && !ready) return SpeakStatus.rejected("TTS_UNAVAILABLE", voiceMode);
            if (!ready) {
                pendingSpeech = new PendingSpeech(normalized, flush, System.currentTimeMillis());
                return SpeakStatus.accepted(true, voiceMode);
            }
        }
        mainHandler.post(() -> speakOnMainThread(normalized, flush));
        return SpeakStatus.accepted(false, voiceMode);
    }

    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            ready = false;
            pendingSpeech = null;
        }
        mainHandler.post(() -> {
            if (engine == null) return;
            try {
                engine.stop();
                engine.shutdown();
            } catch (Exception ignored) {
            } finally {
                engine = null;
            }
        });
    }

    private void initializeOnMainThread() {
        synchronized (lock) {
            if (closed || engine != null) return;
        }
        engine = new TextToSpeech(context, status -> mainHandler.post(() -> onInitialized(status)));
    }

    private void onInitialized(int status) {
        PendingSpeech queued = null;
        synchronized (lock) {
            if (closed) return;
            initialized = true;
            if (status != TextToSpeech.SUCCESS || engine == null) {
                voiceMode = "UNAVAILABLE";
                pendingSpeech = null;
                Log.w(TAG, "System TTS engine initialization failed");
                return;
            }
            int languageStatus = engine.setLanguage(Locale.SIMPLIFIED_CHINESE);
            if (languageStatus == TextToSpeech.LANG_MISSING_DATA || languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
                voiceMode = "CHINESE_UNAVAILABLE";
                pendingSpeech = null;
                Log.w(TAG, "Chinese TTS voice data is unavailable");
                return;
            }
            voiceMode = selectOfflineChineseVoice(engine) ? "OFFLINE" : "SYSTEM_DEFAULT";
            ready = true;
            if (pendingSpeech != null && System.currentTimeMillis() - pendingSpeech.createdAt <= PENDING_TTL_MS) {
                queued = pendingSpeech;
            }
            pendingSpeech = null;
        }
        if (queued != null) speakOnMainThread(queued.text, queued.flush);
    }

    private static boolean selectOfflineChineseVoice(TextToSpeech tts) {
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null) return false;
            for (Voice voice : voices) {
                Locale locale = voice.getLocale();
                if (locale != null
                        && "zh".equalsIgnoreCase(locale.getLanguage())
                        && !voice.isNetworkConnectionRequired()
                        && tts.setVoice(voice) == TextToSpeech.SUCCESS) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to select an offline Chinese TTS voice", e);
        }
        return false;
    }

    private void speakOnMainThread(String text, boolean flush) {
        synchronized (lock) {
            if (closed || !ready || engine == null) return;
        }
        Bundle params = new Bundle();
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.9f);
        int result = engine.speak(
                text,
                flush ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD,
                params,
                "card-tts-" + System.currentTimeMillis());
        if (result != TextToSpeech.SUCCESS) Log.w(TAG, "System TTS did not accept utterance");
    }

    private static String normalizeText(String value) {
        String text = value == null ? "" : value.trim().replaceAll("[\\r\\n]+", " ");
        return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH);
    }

    private static final class PendingSpeech {
        final String text;
        final boolean flush;
        final long createdAt;

        PendingSpeech(String text, boolean flush, long createdAt) {
            this.text = text;
            this.flush = flush;
            this.createdAt = createdAt;
        }
    }

    public static final class SpeakStatus {
        public final boolean accepted;
        public final boolean queuedForInitialization;
        public final String errorCode;
        public final String voiceMode;

        private SpeakStatus(boolean accepted, boolean queuedForInitialization, String errorCode, String voiceMode) {
            this.accepted = accepted;
            this.queuedForInitialization = queuedForInitialization;
            this.errorCode = errorCode;
            this.voiceMode = voiceMode;
        }

        static SpeakStatus accepted(boolean queuedForInitialization, String voiceMode) {
            return new SpeakStatus(true, queuedForInitialization, "", voiceMode);
        }

        static SpeakStatus rejected(String errorCode, String voiceMode) {
            return new SpeakStatus(false, false, errorCode, voiceMode);
        }
    }
}
