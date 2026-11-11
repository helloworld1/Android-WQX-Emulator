#include "nc1020_sound.h"
#include "nc1020_dsp.h"
#include <pthread.h>
#include <string.h>

// 5,120,000 CPU cycles/sec; 8,000 Hz DSP speech rate.
// Using SUB_PER_SAMPLE = 5,120,000 and sub_per_cycle = _output_rate gives an exact
// integer ratio for ANY native hardware FastMixer sample rate (48000, 44100, etc.).
#define CYCLES_PER_SECOND 5120000u
#define SUB_PER_SAMPLE 5120000u
#define DSP_SAMPLE_RATE 8000u
#define CYCLES_PER_DSP_SAMPLE 640u

// Peak raw step = 5,120,000 / 400 = 12,800 (~ -8 dBFS, +/-6400 after DC block).
#define BEEPER_RAW_DIV 400u

#define MAX_BEEPER_EVENTS 4096u
#define AUDIO_RING_CAPACITY 8192u

#define DSP_FIFO_CAPACITY 12000u
#define DSP_BUSY_THRESHOLD 5000u
#define DSP_PREBUFFER_SAMPLES 16u

typedef struct {
    uint64_t sub;
    bool level;
} beeper_event_t;

static pthread_mutex_t _ring_mutex = PTHREAD_MUTEX_INITIALIZER;
static int16_t _ring_buffer[AUDIO_RING_CAPACITY];
static size_t _ring_read = 0;
static size_t _ring_write = 0;
static size_t _ring_count = 0;

static uint32_t _output_rate = NC1020_AUDIO_SAMPLE_RATE;

static beeper_event_t _events[MAX_BEEPER_EVENTS];
static size_t _event_count = 0;

static int16_t _dsp_fifo[DSP_FIFO_CAPACITY];
static size_t _dsp_fifo_read = 0;
static size_t _dsp_fifo_write = 0;
static size_t _dsp_fifo_count = 0;
static int16_t _dsp_s0 = 0;
static int16_t _dsp_s1 = 0;
static uint32_t _dsp_phase = 0;
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

#define TEMP_CHUNK_SIZE 512u
static int16_t _temp_chunk[TEMP_CHUNK_SIZE];
static size_t _temp_count = 0;

static void flush_temp_chunk(bool dsp_playing) {
    if (_temp_count == 0) {
        return;
    }
    size_t max_ring = dsp_playing ? AUDIO_RING_CAPACITY : (_output_rate / 32u);
    if (max_ring > AUDIO_RING_CAPACITY) {
        max_ring = AUDIO_RING_CAPACITY;
    } else if (max_ring < 512u) {
        max_ring = 512u;
    }

    pthread_mutex_lock(&_ring_mutex);
    for (size_t i = 0; i < _temp_count; i++) {
        while (_ring_count >= max_ring) {
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

static inline void emit_sample(int16_t sample, bool dsp_playing) {
    _temp_chunk[_temp_count++] = sample;
    if (_temp_count == TEMP_CHUNK_SIZE) {
        flush_temp_chunk(dsp_playing);
    }
}

void sound_init(void) {
    sound_reset(false);
}

void sound_set_output_sample_rate(uint32_t sample_rate) {
    if (sample_rate < 8000u) {
        sample_rate = 8000u;
    } else if (sample_rate > 192000u) {
        sample_rate = 192000u;
    }
    pthread_mutex_lock(&_ring_mutex);
    _output_rate = sample_rate;
    _ring_read = 0;
    _ring_write = 0;
    _ring_count = 0;
    pthread_mutex_unlock(&_ring_mutex);
    _sub_rem = 0;
    _high_rem = 0;
    _dsp_phase = 0;
}

uint32_t sound_get_output_sample_rate(void) {
    return _output_rate;
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
    _dsp_phase = 0;
    _dsp_active = false;

    _cur_level = initial_level;
    _streaming = false;
    _sub_rem = 0;
    _high_rem = 0;
    _silent_samples = 0;
    _hp_prev_x = initial_level ? (int32_t)(SUB_PER_SAMPLE / BEEPER_RAW_DIV) : 0;
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
        _dsp_phase = 0;
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
    uint64_t sub = rel_cycles * (uint64_t)_output_rate;
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

static inline int32_t next_dsp_upsampled_sample(uint32_t rate) {
    if (!_dsp_active) {
        if (_dsp_fifo_count == 0) {
            return 0;
        }
        if (_dsp_fifo_count < DSP_PREBUFFER_SAMPLES && !dsp_is_sleeping()) {
            return 0;
        }
        _dsp_active = true;
        _dsp_phase = rate;
    }

    while (_dsp_phase >= rate) {
        _dsp_phase -= rate;
        _dsp_s0 = _dsp_s1;
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
                _dsp_phase = 0;
                return 0;
            }
        }
    }

    int32_t out = (int32_t)_dsp_s0 +
                  (int32_t)(((int64_t)((int32_t)_dsp_s1 - (int32_t)_dsp_s0) * (int64_t)_dsp_phase) /
                            (int64_t)rate);
    _dsp_phase += DSP_SAMPLE_RATE;
    return out;
}

void sound_end_slice(uint64_t end_cycle) {
    if (_slice_speed_up || end_cycle <= _slice_start_cycle) {
        return;
    }

    if (!_streaming && _event_count == 0 && _dsp_fifo_count == 0 && !_dsp_active) {
        return;
    }

    const uint32_t rate = _output_rate;
    const uint32_t tail_samples = rate / 50u; // 20 ms DC-block settle tail
    const bool dsp_playing = (_dsp_fifo_count > 0 || _dsp_active);
    uint64_t total_sub = (end_cycle - _slice_start_cycle) * (uint64_t)rate;
    uint64_t pos = 0;
    size_t ev_idx = 0;

    if (!_streaming) {
        _streaming = true;
        _silent_samples = 0;
        _sub_rem = 0;
        _high_rem = 0;
        _hp_prev_x = _cur_level ? (int32_t)(SUB_PER_SAMPLE / BEEPER_RAW_DIV) : 0;
        _hp_y = 0;
        // Jump straight to the exact cycle of the first beep edge so zero leading silence is queued.
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
            int32_t raw = (int32_t)(_high_rem / BEEPER_RAW_DIV);
            int32_t diff = raw - _hp_prev_x;
            _hp_prev_x = raw;
            _hp_y = diff + ((_hp_y * 1015) >> 10);

            int32_t dsp_val = next_dsp_upsampled_sample(rate);
            if (_dsp_fifo_count > 0 || _dsp_active || dsp_val != 0) {
                _silent_samples = 0;
            }

            int32_t sample = _hp_y + dsp_val;
            if (sample > 32767) {
                sample = 32767;
            } else if (sample < -32768) {
                sample = -32768;
            }
            emit_sample((int16_t)sample, dsp_playing || _dsp_active || _dsp_fifo_count > 0);

            _sub_rem = 0;
            _high_rem = 0;
            _silent_samples++;

            if (ev_idx == _event_count && _dsp_fifo_count == 0 && !_dsp_active &&
                _silent_samples >= tail_samples) {
                _streaming = false;
                _silent_samples = 0;
                _hp_y = 0;
                break;
            }
        }
    }

    flush_temp_chunk(dsp_playing || _dsp_active || _dsp_fifo_count > 0);
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

void sound_discard_samples(size_t max_samples) {
    if (max_samples == 0) {
        return;
    }
    pthread_mutex_lock(&_ring_mutex);
    size_t to_drop = (_ring_count < max_samples) ? _ring_count : max_samples;
    _ring_read = (_ring_read + to_drop) % AUDIO_RING_CAPACITY;
    _ring_count -= to_drop;
    pthread_mutex_unlock(&_ring_mutex);
}
