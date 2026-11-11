#ifndef NC1020_H_
#define NC1020_H_

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

void initialize(const char * rom_file_path, const char *nor_file_path, const char *state_file_path);
void reset();
void set_key(uint8_t, bool);
void run_time_slice(uint64_t, bool);
uint8_t* get_lcd_buffer();
void load_nc1020();
void save_nc1020();
uint64_t get_cycles();
size_t read_nc1020_audio(int16_t *buffer, size_t max_samples);
void nc1020_tm0_start(uint8_t counter);
void nc1020_tm0_stop(void);
void nc1020_tm0_set_counter(uint8_t counter);

#endif /* NC1020_H_ */
