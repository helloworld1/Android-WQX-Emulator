#include "nc1020_sound.h"
#include "nc1020_dsp.h"
#include <pthread.h>
#include <string.h>

// 5,120,000 CPU cycles/sec vs 48,000 audio samples/sec -> exact ratio 320 / 3.
// 8,000 Hz DSP speech -> exact 1:6 upsample (6 output samples = 640 CPU cycles).
#define SUB_PER_CYCLE 3u
#define SUB_PER_SAMPLE 320u
#define CYCLES_PER_DSP_SAMPLE 640u
#define DSP_UPSAMPLE_RATIO 6u

// Peak raw step = 320 * 40 = 12800 (~ -8 dBFS, centers around +/-6400 after DC block).
#define BEEPER_GAIN 40

// 4 ms lead-in on wake from idle + 150 ms silence tail so short sounds flush AudioTrack.
#define LEAD_IN_SAMPLES 192u
#define TAIL_SAMPLES 7200u

#define MAX_BEEPER_EVENTS 4096u
#define AUDIO_RING_CAPACITY 48000u

#define DSP_FIFO_CAPACITY 12000u
#define DSP_BUSY_THRESHOLD 5000u
#define DSP_PREBUFFER_SAMPLES 24u

typedef struct {
    uint64_t sub;
    bool level;
} beeper_event_t;

static pthread_mutex_t _ring_mutex = PTHREAD_MUTEX_INITIALIZER;
static int16_t _ring_buffer[AUDIO_RING_CAPACITY];
static size_t _ring_read = 0;
static size_t _ring_write = 0;
static size_t _ring_count = 0;

static beeper_event_t _events[MAX_BEEPER_EVENTS];
static size_t _event_count = 0;

static int16_t _dsp_fifo[DSP_FIFO_CAPACITY];
static size_t _dsp_fifo_read = 0;
static size_t _dsp_fifo_write = 0;
static size_t _dsp_fifo_count = 0;
static int16_t _dsp_s0 = 0;
static int16_t _dsp_s1 = 0;
static uint32_t _dsp_interp_step = 0;
static bool _dsp_active = false;

static uint64_t _slice_start_cycle = 0;
static bool _slice_speed_up = false;

static bool _cur_level = false;
static bool _streaming = false;
static uint32_t _sub_rem = 0;
static uint32_t _high_rem = 0;
static uint32_t _silent_samples = 0;
static int32_t _hp_prev_x = 0;
static int32_t _hp_y = 0;

#define TEMP_CHUNK_SIZE 1024u
static int16_t _temp_chunk[TEMP_CHUNK_SIZE];
static size_t _temp_count = 0;

static void flush_temp_chunk(void) {
    if (_temp_count == 0) {
        return;
    }
    pthread_mutex_lock(&_ring_mutex);
    for (size_t i = 0; i < _temp_count; i++) {
        if (_ring_count == AUDIO_RING_CAPACITY) {
            _ring_read = (_ring_read + 1u) % AUDIO_RING_CAPACITY;
            _ring_count--;
        }
        _ring_buffer[_ring_write] = _temp_chunk[i];
        _ring_write = (_ring_write + 1u) % AUDIO_RING_CAPACITY;
        _ring_count++;
    }
    pthread_mutex_unlock(&_ring_mutex);
    _temp_count = 0;
}

static inline void emit_sample(int16_t sample) {
    _temp_chunk[_temp_count++] = sample;
    if (_temp_count == TEMP_CHUNK_SIZE) {
        flush_temp_chunk();
    }
}

void sound_init(void) {
    sound_reset(false);
}

void sound_reset(bool initial_level) {
    pthread_mutex_lock(&_ring_mutex);
    _ring_read = 0;
    _ring_write = 0;
    _ring_count = 0;
    pthread_mutex_unlock(&_ring_mutex);

    _event_count = 0;
    _temp_count = 0;
    _dsp_fifo_read = 0;
    _dsp_fifo_write = 0;
    _dsp_fifo_count = 0;
    _dsp_s0 = 0;
    _dsp_s1 = 0;
    _dsp_interp_step = 0;
    _dsp_active = false;

    _cur_level = initial_level;
    _streaming = false;
    _sub_rem = 0;
    _high_rem = 0;
    _silent_samples = 0;
    _hp_prev_x = initial_level ? (int32_t)(SUB_PER_SAMPLE * BEEPER_GAIN) : 0;
    _hp_y = 0;
}

void sound_begin_slice(uint64_t start_cycle, bool speed_up) {
    _slice_start_cycle = start_cycle;
    _slice_speed_up = speed_up;
    _event_count = 0;
    if (speed_up) {
        _dsp_fifo_read = 0;
        _dsp_fifo_write = 0;
        _dsp_fifo_count = 0;
        _dsp_s0 = 0;
        _dsp_s1 = 0;
        _dsp_interp_step = 0;
        _dsp_active = false;
        if (_streaming) {
            _streaming = false;
            _sub_rem = 0;
            _high_rem = 0;
            _silent_samples = 0;
            _hp_y = 0;
        }
    }
}

void sound_on_beeper_write(uint64_t cycle, bool level) {
    if (_slice_speed_up) {
        _cur_level = level;
        return;
    }
    uint64_t rel_cycles = (cycle >= _slice_start_cycle) ? (cycle - _slice_start_cycle) : 0;
    uint64_t sub = rel_cycles * SUB_PER_CYCLE;
    if (_event_count < MAX_BEEPER_EVENTS) {
        _events[_event_count].sub = sub;
        _events[_event_count].level = level;
        _event_count++;
    } else {
        _events[MAX_BEEPER_EVENTS - 1u].sub = sub;
        _events[MAX_BEEPER_EVENTS - 1u].level = level;
    }
}

void sound_push_dsp_samples(const int16_t *samples, size_t count) {
    if (_slice_speed_up || samples == NULL || count == 0) {
        return;
    }
    for (size_t i = 0; i < count; i++) {
        if (_dsp_fifo_count >= DSP_FIFO_CAPACITY) {
            break;
        }
        _dsp_fifo[_dsp_fifo_write] = samples[i];
        _dsp_fifo_write = (_dsp_fifo_write + 1u) % DSP_FIFO_CAPACITY;
        _dsp_fifo_count++;
    }
}

bool sound_is_dsp_busy(uint64_t current_cycle) {
    if (_slice_speed_up) {
        return false;
    }
    uint64_t elapsed_dsp = (current_cycle > _slice_start_cycle)
                           ? ((current_cycle - _slice_start_cycle) / CYCLES_PER_DSP_SAMPLE)
                           : 0u;
    size_t effective = (_dsp_fifo_count > elapsed_dsp)
                       ? (_dsp_fifo_count - (size_t)elapsed_dsp)
                       : 0u;
    return effective > DSP_BUSY_THRESHOLD;
}

static inline int32_t next_dsp_upsampled_sample(void) {
    if (!_dsp_active && _dsp_fifo_count == 0) {
        return 0;
    }
    if (_dsp_interp_step == 0u) {
        _dsp_s0 = _dsp_s1;
        if (!_dsp_active && _dsp_fifo_count > 0 &&
            (_dsp_fifo_count >= DSP_PREBUFFER_SAMPLES || dsp_is_sleeping())) {
            _dsp_active = true;
        }
        if (_dsp_active) {
            if (_dsp_fifo_count > 0) {
                _dsp_s1 = _dsp_fifo[_dsp_fifo_read];
                _dsp_fifo_read = (_dsp_fifo_read + 1u) % DSP_FIFO_CAPACITY;
                _dsp_fifo_count--;
            } else {
                int32_t decayed = ((int32_t)_dsp_s0 * 7) / 8;
                if (decayed > -4 && decayed < 4) {
                    decayed = 0;
                }
                _dsp_s1 = (int16_t)decayed;
                if (_dsp_s0 == 0 && _dsp_s1 == 0) {
                    _dsp_active = false;
                }
            }
        }
    }
    if (!_dsp_active && _dsp_s0 == 0 && _dsp_s1 == 0) {
        return 0;
    }
    int32_t out = (int32_t)_dsp_s0 +
                  (((int32_t)_dsp_s1 - (int32_t)_dsp_s0) * (int32_t)_dsp_interp_step) /
                      (int32_t)DSP_UPSAMPLE_RATIO;
    _dsp_interp_step++;
    if (_dsp_interp_step == DSP_UPSAMPLE_RATIO) {
        _dsp_interp_step = 0u;
    }
    return out;
}

void sound_end_slice(uint64_t end_cycle) {
    if (_slice_speed_up || end_cycle <= _slice_start_cycle) {
        return;
    }

    if (!_streaming && _event_count == 0 && _dsp_fifo_count == 0 && !_dsp_active) {
        return;
    }

    uint64_t total_sub = (end_cycle - _slice_start_cycle) * SUB_PER_CYCLE;
    uint64_t pos = 0;
    size_t ev_idx = 0;

    if (!_streaming) {
        _streaming = true;
        _silent_samples = 0;
        _sub_rem = 0;
        _high_rem = 0;
        _dsp_interp_step = 0;
        _hp_prev_x = _cur_level ? (int32_t)(SUB_PER_SAMPLE * BEEPER_GAIN) : 0;
        _hp_y = 0;
        for (uint32_t i = 0; i < LEAD_IN_SAMPLES; i++) {
            emit_sample(0);
        }
        if (_dsp_fifo_count == 0 && !_dsp_active && _event_count > 0 && _events[0].sub < total_sub) {
            pos = _events[0].sub;
        }
    }

    while (pos < total_sub || (ev_idx < _event_count && _events[ev_idx].sub <= pos)) {
        while (ev_idx < _event_count && _events[ev_idx].sub <= pos) {
            if (_cur_level != _events[ev_idx].level) {
                _cur_level = _events[ev_idx].level;
                _silent_samples = 0;
            }
            ev_idx++;
        }
        if (pos >= total_sub) {
            break;
        }

        uint64_t next_ev_sub = (ev_idx < _event_count) ? _events[ev_idx].sub : total_sub;
        if (next_ev_sub > total_sub) {
            next_ev_sub = total_sub;
        }

        uint64_t need_for_sample = SUB_PER_SAMPLE - _sub_rem;
        uint64_t step = next_ev_sub - pos;
        if (step > need_for_sample) {
            step = need_for_sample;
        }

        if (_cur_level) {
            _high_rem += (uint32_t)step;
        }
        _sub_rem += (uint32_t)step;
        pos += step;

        if (_sub_rem == SUB_PER_SAMPLE) {
            int32_t raw = (int32_t)(_high_rem * BEEPER_GAIN);
            int32_t diff = raw - _hp_prev_x;
            _hp_prev_x = raw;
            _hp_y = diff + ((_hp_y * 1019) >> 10);

            int32_t dsp_val = next_dsp_upsampled_sample();
            if (_dsp_fifo_count > 0 || _dsp_active || dsp_val != 0) {
                _silent_samples = 0;
            }

            int32_t sample = _hp_y + dsp_val;
            if (sample > 32767) {
                sample = 32767;
            } else if (sample < -32768) {
                sample = -32768;
            }
            emit_sample((int16_t)sample);

            _sub_rem = 0;
            _high_rem = 0;
            _silent_samples++;

            if (ev_idx == _event_count && _dsp_fifo_count == 0 && !_dsp_active &&
                _silent_samples >= TAIL_SAMPLES) {
                _streaming = false;
                _silent_samples = 0;
                _hp_y = 0;
                break;
            }
        }
    }

    flush_temp_chunk();
}

size_t read_audio_samples(int16_t *out_buffer, size_t max_samples) {
    if (out_buffer == NULL || max_samples == 0) {
        return 0;
    }
    pthread_mutex_lock(&_ring_mutex);
    size_t to_read = (_ring_count < max_samples) ? _ring_count : max_samples;
    for (size_t i = 0; i < to_read; i++) {
        out_buffer[i] = _ring_buffer[_ring_read];
        _ring_read = (_ring_read + 1u) % AUDIO_RING_CAPACITY;
    }
    _ring_count -= to_read;
    pthread_mutex_unlock(&_ring_mutex);
    return to_read;
}
