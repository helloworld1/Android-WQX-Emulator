#ifndef NC1020_SOUND_H_
#define NC1020_SOUND_H_

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define NC1020_AUDIO_SAMPLE_RATE 48000

void sound_init(void);
void sound_reset(bool initial_level);
void sound_begin_slice(uint64_t start_cycle, bool speed_up);
void sound_on_beeper_write(uint64_t cycle, bool level);
void sound_push_dsp_samples(const int16_t *samples, size_t count);
bool sound_is_dsp_busy(uint64_t current_cycle);
void sound_end_slice(uint64_t end_cycle);
size_t read_audio_samples(int16_t *out_buffer, size_t max_samples);

#endif /* NC1020_SOUND_H_ */
