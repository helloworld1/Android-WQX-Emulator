#ifndef NC1020_H_
#define NC1020_H_

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

void initialize(const char * rom_file_path, const char *nor_file_path, const char *state_file_path);
void reset();
void set_key(uint8_t, bool);
void run_time_slice(uint64_t, bool);
bool copy_lcd_buffer(uint8_t *);
void load_nc1020();
void save_nc1020();
uint64_t get_cycles();

#endif /* NC1020_H_ */
