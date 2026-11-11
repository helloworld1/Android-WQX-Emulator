#include "android_audio.h"
#include "./wqx/nc1020_sound.h"

#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <android/api-level.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>

#define LOG_TAG "NC1020Audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// ---- Dynamic AAudio declarations (compatible with minSdk 21, active on API 27+) ----
typedef struct AAudioStreamStruct AAudioStream;
typedef struct AAudioStreamBuilderStruct AAudioStreamBuilder;
typedef int32_t aaudio_result_t;
typedef int32_t aaudio_data_callback_result_t;

#define AAUDIO_OK 0
#define AAUDIO_DIRECTION_OUTPUT 0
#define AAUDIO_FORMAT_PCM_I16 1
#define AAUDIO_SHARING_MODE_SHARED 0
#define AAUDIO_PERFORMANCE_MODE_LOW_LATENCY 12
#define AAUDIO_USAGE_GAME 14
#define AAUDIO_CONTENT_TYPE_SONIFICATION 4
#define AAUDIO_CALLBACK_RESULT_CONTINUE 0

typedef aaudio_data_callback_result_t (*aaudio_data_cb_fn)(
        AAudioStream *stream, void *userData, void *audioData, int32_t numFrames);
typedef void (*aaudio_error_cb_fn)(
        AAudioStream *stream, void *userData, aaudio_result_t error);

typedef struct {
    void *lib_handle;
    bool loaded;
    aaudio_result_t (*createStreamBuilder)(AAudioStreamBuilder **builder);
    aaudio_result_t (*builder_delete)(AAudioStreamBuilder *builder);
    void (*builder_setDirection)(AAudioStreamBuilder *builder, int32_t direction);
    void (*builder_setSharingMode)(AAudioStreamBuilder *builder, int32_t sharingMode);
    void (*builder_setPerformanceMode)(AAudioStreamBuilder *builder, int32_t mode);
    void (*builder_setFormat)(AAudioStreamBuilder *builder, int32_t format);
    void (*builder_setChannelCount)(AAudioStreamBuilder *builder, int32_t channelCount);
    void (*builder_setUsage)(AAudioStreamBuilder *builder, int32_t usage);
    void (*builder_setContentType)(AAudioStreamBuilder *builder, int32_t contentType);
    void (*builder_setDataCallback)(AAudioStreamBuilder *builder, aaudio_data_cb_fn cb, void *userData);
    void (*builder_setErrorCallback)(AAudioStreamBuilder *builder, aaudio_error_cb_fn cb, void *userData);
    aaudio_result_t (*builder_openStream)(AAudioStreamBuilder *builder, AAudioStream **stream);
    aaudio_result_t (*stream_requestStart)(AAudioStream *stream);
    aaudio_result_t (*stream_requestStop)(AAudioStream *stream);
    aaudio_result_t (*stream_close)(AAudioStream *stream);
    int32_t (*stream_getSampleRate)(AAudioStream *stream);
    int32_t (*stream_getFramesPerBurst)(AAudioStream *stream);
    aaudio_result_t (*stream_setBufferSizeInFrames)(AAudioStream *stream, int32_t numFrames);
    int32_t (*stream_getPerformanceMode)(AAudioStream *stream);
} aaudio_api_t;

static aaudio_api_t _aa = {0};
static pthread_mutex_t _lifecycle_mutex = PTHREAD_MUTEX_INITIALIZER;
static atomic_bool _audio_enabled = true;
static bool _want_running = false;
static bool _using_aaudio = false;
static bool _using_opensl = false;

static AAudioStream *_aa_stream = NULL;

// ---- OpenSL ES fallback state (API 21..26) ----
#define OPENSL_FRAMES_PER_BUF 192u
#define OPENSL_NUM_BUFS 2u
static SLObjectItf _sl_engine_obj = NULL;
static SLEngineItf _sl_engine = NULL;
static SLObjectItf _sl_mix_obj = NULL;
static SLObjectItf _sl_player_obj = NULL;
static SLPlayItf _sl_play = NULL;
static SLAndroidSimpleBufferQueueItf _sl_bq = NULL;
static int16_t _sl_buffers[OPENSL_NUM_BUFS][OPENSL_FRAMES_PER_BUF];
static uint32_t _sl_buf_idx = 0;

static void fill_output_frames(int16_t *out, size_t frames) {
    if (!atomic_load_explicit(&_audio_enabled, memory_order_relaxed)) {
        sound_discard_samples(frames);
        memset(out, 0, frames * sizeof(int16_t));
        return;
    }
    size_t got = read_audio_samples(out, frames);
    if (got < frames) {
        memset(out + got, 0, (frames - got) * sizeof(int16_t));
    }
}

static bool load_aaudio_api(void) {
    if (_aa.loaded) {
        return true;
    }
    if (android_get_device_api_level() < 27) {
        return false;
    }
    void *h = dlopen("libaaudio.so", RTLD_NOW);
    if (h == NULL) {
        return false;
    }
    _aa.lib_handle = h;
    _aa.createStreamBuilder = dlsym(h, "AAudio_createStreamBuilder");
    _aa.builder_delete = dlsym(h, "AAudioStreamBuilder_delete");
    _aa.builder_setDirection = dlsym(h, "AAudioStreamBuilder_setDirection");
    _aa.builder_setSharingMode = dlsym(h, "AAudioStreamBuilder_setSharingMode");
    _aa.builder_setPerformanceMode = dlsym(h, "AAudioStreamBuilder_setPerformanceMode");
    _aa.builder_setFormat = dlsym(h, "AAudioStreamBuilder_setFormat");
    _aa.builder_setChannelCount = dlsym(h, "AAudioStreamBuilder_setChannelCount");
    _aa.builder_setUsage = dlsym(h, "AAudioStreamBuilder_setUsage");
    _aa.builder_setContentType = dlsym(h, "AAudioStreamBuilder_setContentType");
    _aa.builder_setDataCallback = dlsym(h, "AAudioStreamBuilder_setDataCallback");
    _aa.builder_setErrorCallback = dlsym(h, "AAudioStreamBuilder_setErrorCallback");
    _aa.builder_openStream = dlsym(h, "AAudioStreamBuilder_openStream");
    _aa.stream_requestStart = dlsym(h, "AAudioStream_requestStart");
    _aa.stream_requestStop = dlsym(h, "AAudioStream_requestStop");
    _aa.stream_close = dlsym(h, "AAudioStream_close");
    _aa.stream_getSampleRate = dlsym(h, "AAudioStream_getSampleRate");
    _aa.stream_getFramesPerBurst = dlsym(h, "AAudioStream_getFramesPerBurst");
    _aa.stream_setBufferSizeInFrames = dlsym(h, "AAudioStream_setBufferSizeInFrames");
    _aa.stream_getPerformanceMode = dlsym(h, "AAudioStream_getPerformanceMode");

    if (!_aa.createStreamBuilder || !_aa.builder_delete || !_aa.builder_setDirection ||
        !_aa.builder_setSharingMode || !_aa.builder_setPerformanceMode || !_aa.builder_setFormat ||
        !_aa.builder_setChannelCount || !_aa.builder_setDataCallback ||
        !_aa.builder_setErrorCallback || !_aa.builder_openStream || !_aa.stream_requestStart ||
        !_aa.stream_requestStop || !_aa.stream_close || !_aa.stream_getSampleRate ||
        !_aa.stream_getFramesPerBurst || !_aa.stream_setBufferSizeInFrames) {
        dlclose(h);
        memset(&_aa, 0, sizeof(_aa));
        return false;
    }
    _aa.loaded = true;
    return true;
}

static bool start_aaudio_locked(void);
static void stop_aaudio_locked(void);

static aaudio_data_callback_result_t aaudio_data_cb(
        AAudioStream *stream, void *userData, void *audioData, int32_t numFrames) {
    (void)stream;
    (void)userData;
    if (numFrames > 0 && audioData != NULL) {
        fill_output_frames((int16_t *)audioData, (size_t)numFrames);
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void *aaudio_restart_thread(void *arg) {
    AAudioStream *failed_stream = (AAudioStream *)arg;
    pthread_mutex_lock(&_lifecycle_mutex);
    if (_aa_stream == failed_stream && _using_aaudio) {
        stop_aaudio_locked();
        if (_want_running) {
            start_aaudio_locked();
        }
    }
    pthread_mutex_unlock(&_lifecycle_mutex);
    return NULL;
}

static void aaudio_error_cb(AAudioStream *stream, void *userData, aaudio_result_t error) {
    (void)userData;
    LOGW("AAudio stream disconnected/error (%d), restarting route", error);
    pthread_t tid;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_create(&tid, &attr, aaudio_restart_thread, stream);
    pthread_attr_destroy(&attr);
}

static bool start_aaudio_locked(void) {
    if (!load_aaudio_api()) {
        return false;
    }
    AAudioStreamBuilder *builder = NULL;
    if (_aa.createStreamBuilder(&builder) != AAUDIO_OK || builder == NULL) {
        return false;
    }

    _aa.builder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    _aa.builder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    _aa.builder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    _aa.builder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    _aa.builder_setChannelCount(builder, 1);
    if (_aa.builder_setUsage) {
        _aa.builder_setUsage(builder, AAUDIO_USAGE_GAME);
    }
    if (_aa.builder_setContentType) {
        _aa.builder_setContentType(builder, AAUDIO_CONTENT_TYPE_SONIFICATION);
    }
    _aa.builder_setDataCallback(builder, aaudio_data_cb, NULL);
    _aa.builder_setErrorCallback(builder, aaudio_error_cb, NULL);

    AAudioStream *stream = NULL;
    aaudio_result_t res = _aa.builder_openStream(builder, &stream);
    _aa.builder_delete(builder);
    if (res != AAUDIO_OK || stream == NULL) {
        return false;
    }

    int32_t sample_rate = _aa.stream_getSampleRate(stream);
    if (sample_rate > 0) {
        sound_set_output_sample_rate((uint32_t)sample_rate);
    }

    int32_t burst = _aa.stream_getFramesPerBurst(stream);
    if (burst > 0) {
        _aa.stream_setBufferSizeInFrames(stream, burst * 2);
    }

    if (_aa.stream_requestStart(stream) != AAUDIO_OK) {
        _aa.stream_close(stream);
        return false;
    }

    _aa_stream = stream;
    _using_aaudio = true;
    int32_t perf_mode = _aa.stream_getPerformanceMode ? _aa.stream_getPerformanceMode(stream) : -1;
    LOGI("AAudio started: rate=%d Hz, burst=%d frames, perfMode=%d", sample_rate, burst, perf_mode);
    return true;
}

static void stop_aaudio_locked(void) {
    if (_aa_stream != NULL) {
        _aa.stream_requestStop(_aa_stream);
        _aa.stream_close(_aa_stream);
        _aa_stream = NULL;
    }
    _using_aaudio = false;
}

static void opensl_bq_cb(SLAndroidSimpleBufferQueueItf bq, void *context) {
    (void)context;
    int16_t *buf = _sl_buffers[_sl_buf_idx];
    _sl_buf_idx = (_sl_buf_idx + 1u) % OPENSL_NUM_BUFS;
    fill_output_frames(buf, OPENSL_FRAMES_PER_BUF);
    (*bq)->Enqueue(bq, buf, OPENSL_FRAMES_PER_BUF * sizeof(int16_t));
}

static void stop_opensl_locked(void) {
    if (_sl_play != NULL) {
        (*_sl_play)->SetPlayState(_sl_play, SL_PLAYSTATE_STOPPED);
    }
    if (_sl_bq != NULL) {
        (*_sl_bq)->Clear(_sl_bq);
    }
    if (_sl_player_obj != NULL) {
        (*_sl_player_obj)->Destroy(_sl_player_obj);
        _sl_player_obj = NULL;
        _sl_play = NULL;
        _sl_bq = NULL;
    }
    if (_sl_mix_obj != NULL) {
        (*_sl_mix_obj)->Destroy(_sl_mix_obj);
        _sl_mix_obj = NULL;
    }
    if (_sl_engine_obj != NULL) {
        (*_sl_engine_obj)->Destroy(_sl_engine_obj);
        _sl_engine_obj = NULL;
        _sl_engine = NULL;
    }
    _using_opensl = false;
}

static bool start_opensl_locked(void) {
    sound_set_output_sample_rate(NC1020_AUDIO_SAMPLE_RATE);

    if (slCreateEngine(&_sl_engine_obj, 0, NULL, 0, NULL, NULL) != SL_RESULT_SUCCESS ||
        (*_sl_engine_obj)->Realize(_sl_engine_obj, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS ||
        (*_sl_engine_obj)->GetInterface(_sl_engine_obj, SL_IID_ENGINE, &_sl_engine) != SL_RESULT_SUCCESS) {
        stop_opensl_locked();
        return false;
    }

    if ((*_sl_engine)->CreateOutputMix(_sl_engine, &_sl_mix_obj, 0, NULL, NULL) != SL_RESULT_SUCCESS ||
        (*_sl_mix_obj)->Realize(_sl_mix_obj, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS) {
        stop_opensl_locked();
        return false;
    }

    SLDataLocator_AndroidSimpleBufferQueue loc_bq = {
        SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, OPENSL_NUM_BUFS
    };
    SLDataFormat_PCM format_pcm = {
        SL_DATAFORMAT_PCM,
        1,
        SL_SAMPLINGRATE_48,
        SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_SPEAKER_FRONT_CENTER,
        SL_BYTEORDER_LITTLEENDIAN
    };
    SLDataSource audio_src = {&loc_bq, &format_pcm};

    SLDataLocator_OutputMix loc_outmix = {SL_DATALOCATOR_OUTPUTMIX, _sl_mix_obj};
    SLDataSink audio_snk = {&loc_outmix, NULL};

    const SLInterfaceID ids[1] = {SL_IID_BUFFERQUEUE};
    const SLboolean req[1] = {SL_BOOLEAN_TRUE};

    if ((*_sl_engine)->CreateAudioPlayer(_sl_engine, &_sl_player_obj, &audio_src, &audio_snk,
                                         1, ids, req) != SL_RESULT_SUCCESS ||
        (*_sl_player_obj)->Realize(_sl_player_obj, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS ||
        (*_sl_player_obj)->GetInterface(_sl_player_obj, SL_IID_PLAY, &_sl_play) != SL_RESULT_SUCCESS ||
        (*_sl_player_obj)->GetInterface(_sl_player_obj, SL_IID_BUFFERQUEUE, &_sl_bq) != SL_RESULT_SUCCESS ||
        (*_sl_bq)->RegisterCallback(_sl_bq, opensl_bq_cb, NULL) != SL_RESULT_SUCCESS) {
        stop_opensl_locked();
        return false;
    }

    _sl_buf_idx = 0;
    memset(_sl_buffers, 0, sizeof(_sl_buffers));
    for (uint32_t i = 0; i < OPENSL_NUM_BUFS; i++) {
        (*_sl_bq)->Enqueue(_sl_bq, _sl_buffers[i], OPENSL_FRAMES_PER_BUF * sizeof(int16_t));
    }

    if ((*_sl_play)->SetPlayState(_sl_play, SL_PLAYSTATE_PLAYING) != SL_RESULT_SUCCESS) {
        stop_opensl_locked();
        return false;
    }

    _using_opensl = true;
    LOGI("OpenSL ES low-latency stream started (48000 Hz, %u x %u frames)",
         OPENSL_NUM_BUFS, OPENSL_FRAMES_PER_BUF);
    return true;
}

void android_audio_start(void) {
    pthread_mutex_lock(&_lifecycle_mutex);
    _want_running = true;
    if (!_using_aaudio && !_using_opensl) {
        if (!start_aaudio_locked()) {
            start_opensl_locked();
        }
    }
    pthread_mutex_unlock(&_lifecycle_mutex);
}

void android_audio_stop(void) {
    pthread_mutex_lock(&_lifecycle_mutex);
    _want_running = false;
    if (_using_aaudio) {
        stop_aaudio_locked();
    }
    if (_using_opensl) {
        stop_opensl_locked();
    }
    pthread_mutex_unlock(&_lifecycle_mutex);
}

void android_audio_set_enabled(bool enabled) {
    atomic_store_explicit(&_audio_enabled, enabled, memory_order_relaxed);
}
